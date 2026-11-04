"""Run real librime pack compilation/candidate checks in an isolated Windows directory."""

import argparse
import ctypes as c
import json
from pathlib import Path
import shutil
import tempfile
import time


class Traits(c.Structure):
    _fields_ = [("data_size", c.c_int)] + [(name, c.c_char_p) for name in (
        "shared_data_dir", "user_data_dir", "distribution_name",
        "distribution_code_name", "distribution_version", "app_name",
    )] + [("modules", c.POINTER(c.c_char_p)), ("min_log_level", c.c_int)] + [
        (name, c.c_char_p) for name in ("log_dir", "prebuilt_data_dir", "staging_dir")
    ]


class Candidate(c.Structure):
    _fields_ = [("text", c.c_char_p), ("comment", c.c_char_p), ("reserved", c.c_void_p)]


class Iterator(c.Structure):
    _fields_ = [("ptr", c.c_void_p), ("index", c.c_int), ("candidate", Candidate)]


SCHEMAS = ("rime_frost", "rime_frost_double_pinyin_flypy", "rime_frost_mixed_flypy")
PACKS = (
    ("xime_pack_genshin", "血红之证", "xue hong zhi zheng", "xuehongzhizheng", "xthsvivg"),
    ("xime_genshin_pack_b", "珐露珊邀约任务", "fa lu shan yao yue ren wu", "falushanyaoyuerenwu", "faluujycytrfwu"),
)
GENSHIN_QUERIES = (
    ("芙宁娜", "funingna", "funkna"),
)
HOTWORD_QUERIES = (
    ("丞磊", "chenglei", "iglw"),
    ("张凌赫", "zhanglinghe", "vhlkhe"),
    ("田曦薇", "tianxiwei", "tmxiww"),
    ("逐玉", "zhuyu", "vuyu"),
)
DEPENDENCIES = ("rime_frost_aux", "rime_frost_melt_eng", "rime_frost_radical_pinyin")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dll", type=Path, required=True)
    parser.add_argument("--source", type=Path, required=True, help="Production source exported by Android DictionaryPagesLiveTest")
    parser.add_argument("--hotwords", type=Path, help="Canonical hotwords exported by the real Pages download test")
    parser.add_argument("--simulate-failure", action="store_true", help="Force a real compile failure, restore managed files and query again")
    parser.add_argument("--assets", type=Path, default=Path("app/src/main/assets"))
    parser.add_argument("--output", type=Path, default=Path("build/rime-pack-smoke"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.output)).resolve()
    data = work / "data"
    shutil.copytree(args.assets / "rime", data)
    shutil.copytree(args.assets / "rime-frost", data, dirs_exist_ok=True)
    if args.hotwords:
        source = args.hotwords.read_text(encoding="utf-8")
        assert "name: xime_pack_hotwords\n" in source
        (data / "xime_pack_hotwords.dict.yaml").write_text(source, encoding="utf-8")
    (data / "default.custom.yaml").write_text(
        "patch:\n  schema_list:\n" + "".join(f"    - schema: {s}\n" for s in SCHEMAS), encoding="utf-8"
    )
    for name, word, pinyin, _, _ in PACKS:
        if name == PACKS[0][0]:
            source = args.source.read_text(encoding="utf-8")
            assert f"name: {name}\n" in source, "Source dictionary name differs from Android managed name"
            (data / f"{name}.dict.yaml").write_text(source, encoding="utf-8")
            continue
        (data / f"{name}.dict.yaml").write_text(
            f'---\nname: {name}\nversion: "1"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n'
            f"{word}\t{pinyin}\t100000\n", encoding="utf-8"
        )
    for name in DEPENDENCIES:
        (data / f"{name}.schema.yaml").write_text(
            f'schema:\n  schema_id: {name}\n  name: Test compilation dependency\n  version: "1"\n'
            f'translator:\n  dictionary: {name}\n', encoding="utf-8"
        )
    r = c.CDLL(str(args.dll.resolve()))

    def bind(name, result, *params):
        fn = getattr(r, name)
        fn.restype = result
        fn.argtypes = params
        return fn

    setup = bind("RimeSetup", None, c.POINTER(Traits))
    initialize = bind("RimeInitialize", None, c.POINTER(Traits))
    finalize = bind("RimeFinalize", None)
    deploy_schema = bind("RimeDeploySchema", c.c_int, c.c_char_p)
    deploy_config = bind("RimeDeployConfigFile", c.c_int, c.c_char_p, c.c_char_p)
    create = bind("RimeCreateSession", c.c_size_t)
    destroy = bind("RimeDestroySession", c.c_int, c.c_size_t)
    select = bind("RimeSelectSchema", c.c_int, c.c_size_t, c.c_char_p)
    option = bind("RimeSetOption", None, c.c_size_t, c.c_char_p, c.c_int)
    simulate = bind("RimeSimulateKeySequence", c.c_int, c.c_size_t, c.c_char_p)
    begin = bind("RimeCandidateListBegin", c.c_int, c.c_size_t, c.POINTER(Iterator))
    next_candidate = bind("RimeCandidateListNext", c.c_int, c.POINTER(Iterator))
    end = bind("RimeCandidateListEnd", None, c.POINTER(Iterator))
    modules = (c.c_char_p * 4)(b"default", b"deployer", b"lua", None)
    traits = Traits(data_size=c.sizeof(Traits) - c.sizeof(c.c_int),
                    shared_data_dir=str(data).encode(), user_data_dir=str(data).encode(),
                    app_name=b"rime.xime-pack-smoke", modules=modules, min_log_level=2,
                    log_dir=str(work).encode())
    setup(c.byref(traits))
    initialize(c.byref(traits))
    evidence = {"directory": str(work), "stages": []}

    def candidates(schema, keys):
        session = create()
        assert session and select(session, schema.encode()), f"Cannot load {schema}"
        try:
            option(session, b"ascii_mode", 0)
            option(session, b"simplification", 1)
            assert simulate(session, keys.encode()), f"Input rejected: {schema}: {keys}"
            iterator = Iterator()
            result = []
            if begin(session, c.byref(iterator)):
                try:
                    while len(result) < 200 and next_candidate(c.byref(iterator)):
                        result.append(iterator.candidate.text.decode("utf-8"))
                finally:
                    end(c.byref(iterator))
            return result
        finally:
            destroy(session)

    try:
        assert deploy_config(b"default.yaml", b"config_version"), "Default config compile failed"
        for name in DEPENDENCIES:
            assert deploy_schema(str(data / f"{name}.schema.yaml").encode()), f"Dependency compile failed: {name}"
        stages = [("enabled", PACKS), ("disabled-a", PACKS[1:]), ("restored", PACKS)]
        if args.hotwords:
            stages.insert(0, ("baseline-hotwords", PACKS))
        for stage, active in stages:
            hotwords_active = args.hotwords and stage in {"enabled", "restored"}
            for schema in SCHEMAS:
                (data / f"{schema}.custom.yaml").write_text(
                    'patch:\n  "schema/dependencies/+":\n'
                    + "".join(f"    - {name}\n" for name in DEPENDENCIES)
                    + '  "translator/enable_sentence": false\n'
                    '  "translator/enable_user_dict": false\n  "translator/packs/+":\n'
                    + "".join(f"    - {pack[0]}\n" for pack in active), encoding="utf-8"
                )
                if hotwords_active:
                    patch = data / f"{schema}.custom.yaml"
                    with patch.open("a", encoding="utf-8") as stream:
                        stream.write("    - xime_pack_hotwords\n")
            started = time.monotonic()
            for pack in active:
                for suffix in ("table.bin", "reverse.bin"):
                    (data / "build" / f"{pack[0]}.{suffix}").unlink(missing_ok=True)
            if args.hotwords:
                for suffix in ("table.bin", "reverse.bin"):
                    (data / "build" / f"xime_pack_hotwords.{suffix}").unlink(missing_ok=True)
            for schema in SCHEMAS:
                (data / "build" / f"{schema}.schema.yaml").unlink(missing_ok=True)
                assert deploy_schema(str(data / f"{schema}.schema.yaml").encode()), f"Incremental schema compile failed: {schema}"
            checks = []
            for schema in SCHEMAS:
                for pack in PACKS:
                    name, word, _, full, double = pack
                    inputs = (full,) if schema == SCHEMAS[0] else (double,) if schema == SCHEMAS[1] else (full, double)
                    for keys in inputs:
                        actual = candidates(schema, keys)
                        expected = pack in active
                        assert (word in actual) == expected, f"{stage} {schema} {keys}: expected={expected}, candidates={actual}"
                        checks.append({"schema": schema, "input": keys, "word": word, "present": expected})
                for word, full, double in GENSHIN_QUERIES:
                    inputs = (full,) if schema == SCHEMAS[0] else (double,) if schema == SCHEMAS[1] else (full, double)
                    expected = any(pack[0] == PACKS[0][0] for pack in active)
                    for keys in inputs:
                        actual = candidates(schema, keys)
                        assert (word in actual) == expected, f"{stage} {schema} {keys}: expected={expected}, candidates={actual}"
                        checks.append({"schema": schema, "input": keys, "word": word, "present": expected})
                if args.hotwords:
                    for word, full, double in HOTWORD_QUERIES:
                        inputs = (full,) if schema == SCHEMAS[0] else (double,) if schema == SCHEMAS[1] else (full, double)
                        for keys in inputs:
                            actual = candidates(schema, keys)
                            expected = bool(hotwords_active)
                            assert (word in actual) == expected, f"{stage} {schema} {keys}: expected={expected}, candidates={actual}"
                            checks.append({"schema": schema, "input": keys, "word": word, "present": expected,
                                           "rank": actual.index(word) + 1 if word in actual else None})
                    full, double = "nihao", "nihc"
                    inputs = (full,) if schema == SCHEMAS[0] else (double,) if schema == SCHEMAS[1] else (full, double)
                    for keys in inputs:
                        assert "你好" in candidates(schema, keys), f"Ordinary word lost: {stage}: {schema}"
                        checks.append({"schema": schema, "input": keys, "word": "你好", "present": True})
            for pack in active:
                table = data / "build" / f"{pack[0]}.table.bin"
                assert table.is_file() and table.stat().st_size > 0, f"Missing compiled pack {table}"
            if hotwords_active:
                assert (data / "build/xime_pack_hotwords.table.bin").is_file(), "Hotwords pack was not compiled"
            evidence["stages"].append({"stage": stage, "deployment": "RimeDeploySchema", "seconds": round(time.monotonic() - started, 3), "checks": checks})
            (work / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"{stage}: {len(checks)} real candidate checks passed", flush=True)
        if args.simulate_failure:
            failed_name = "xime_pack_hotwords" if args.hotwords else PACKS[0][0]
            tracked = [data / f"{failed_name}.dict.yaml"]
            tracked += [data / "build" / f"{pack[0]}.{suffix}" for pack in PACKS for suffix in ("table.bin", "reverse.bin")]
            if args.hotwords:
                tracked += [data / "build" / f"{failed_name}.{suffix}" for suffix in ("table.bin", "reverse.bin")]
            tracked += [data / "build" / f"{schema}.{suffix}" for schema in SCHEMAS for suffix in ("schema.yaml", "prism.bin")]
            snapshot = {path: path.read_bytes() if path.exists() else None for path in tracked}
            try:
                tracked[0].write_text(f"---\nname: {failed_name}\nversion: broken\nimport_tables: [xime_nonexistent_dependency]\n...\n", encoding="utf-8")
                table = data / "build" / f"{failed_name}.table.bin"
                table.unlink()
                compiler_result = bool(deploy_schema(str(data / f"{SCHEMAS[0]}.schema.yaml").encode()))
                assert not table.exists(), "Broken pack unexpectedly produced a compiled table"
            finally:
                for path, content in snapshot.items():
                    if content is None:
                        path.unlink(missing_ok=True)
                    else:
                        path.write_bytes(content)
            checks = 0
            for schema in SCHEMAS:
                for pack in PACKS:
                    inputs = (pack[3],) if schema == SCHEMAS[0] else (pack[4],) if schema == SCHEMAS[1] else (pack[3], pack[4])
                    for keys in inputs:
                        assert pack[1] in candidates(schema, keys), f"Candidate lost after failed update rollback: {schema}"
                        checks += 1
                for word, full, double in GENSHIN_QUERIES + (HOTWORD_QUERIES if args.hotwords else ()):
                    inputs = (full,) if schema == SCHEMAS[0] else (double,) if schema == SCHEMAS[1] else (full, double)
                    for keys in inputs:
                        assert word in candidates(schema, keys), f"Candidate lost after failed update rollback: {schema}: {word}"
                        checks += 1
            evidence["failure_rollback"] = {"compiler_returned": compiler_result, "pack_artifact_missing_detected": True, "restored_candidate_checks": checks}
            (work / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            print(f"real compile failure + managed file rollback: {checks} candidate checks passed", flush=True)
    finally:
        finalize()
    print(work / "evidence.json")


if __name__ == "__main__":
    main()
