# Frost resources bundled by Xime

- Upstream: `gaboolic/rime-frost`
- Pinned commit: `f1bc68b308adcca916c51bcaad25a267876e6efe`
- License: GPL-3.0-or-later; see `LICENSE`.
- Imported: 2026-10-12.

## Included runtime dependencies

- Official full-pinyin schema `rime_frost.schema.yaml` and official Flypy schema `rime_frost_double_pinyin_flypy.schema.yaml`.
- The base dictionary's core imports under `rime_frost_data/cn_dicts/`.
- The 23 optional cell dictionaries are packaged in `Xime-Frost-Cell-Dictionaries.zip` and imported as `rime_frost_cells`.
- Both active English tables imported by the bundled `rime_frost_melt_eng.dict.yaml`, plus the English translator dictionary itself.
- Radical lookup and auxiliary-code dictionaries, the Frost custom-phrase seed, and the referenced `rime_frost_symbols_v.yaml`.
- Fourteen referenced Frost Lua modules and `lua/rime_frost_aux_code/moqi_aux_code.txt`.
- Six referenced OpenCC configurations and their text/binary dictionaries.

Dictionary imports, Lua module names, custom-phrase storage, and OpenCC filenames are namespaced so deployment does not replace Xime's Wubi resources or a user's `custom_phrase.txt`. The schemas configure their translators and dictionaries inline, so the three upstream `schema/dependencies` entries were removed: the pinned upstream snapshot does not ship those dependency schema files, and the required data is already bundled directly.

`rime_frost_mixed_flypy.schema.yaml` is based on the official Flypy schema. Its official Flypy `xform` rules are changed to `derive` rules while the official `xlit` is retained, making full spellings and Flypy spellings aliases in one schema and one script translator. A local Windows librime run checked 250 high-frequency dictionary entries and 964 per-syllable full/Flypy combinations in one schema/composition: all target candidates were present, candidate ranks matched the dedicated Flypy schema for the baseline set, and no duplicate candidates appeared in the first 200 results. Android JNI and real-device input, backspace editing, and long-session behavior still require device validation.

The pack omits the commented-out Tencent dictionary, unrelated schemas, and unreferenced optional dictionaries/configurations. `default.yaml` remains owned by Xime. Updates use a per-file content manifest and preserve user-modified files.
