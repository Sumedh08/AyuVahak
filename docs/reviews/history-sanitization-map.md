# Pre-publication Git history sanitization map

**Date:** 2026-10-21
**Scope:** one-time sanitization before the first publication of `Sumedh08/AyuVahak`.

The original repository was preserved in an immutable local Git mirror and bundle before rewriting. The bundle SHA-256 is `B62F914022FB983FA0A6CF300F5D1E6FE8582C2164B2877449675871F5B17E5D`. The original ignored `.scratch` working-tree material was preserved separately outside the repository. The target GitHub repository had no branches or tags when inspected.

The authoritative Phase 13 branch history was rewritten with `git-filter-repo` 2.47.0 to remove tracked `.scratch/**` paths from every commit. Empty commits were retained (`--prune-empty never`): all 271 original commits remain in order with their messages and authorship. The sanitized tip adds a documentation-only follow-up. No pre-existing tags were present; annotated freeze tags are recreated at mapped commits. Other paths were not filtered.

The complete old-to-new commit map is preserved locally alongside the backup. This table records important freeze, governance, authorization, and acceptance checkpoints. Other commit references in tracked documentation and `AGENTS.md` were updated to their mapped IDs.

| Original checkpoint | Original commit | Sanitized commit |
|---|---|---|
| Phase 1 freeze | `0e8fae5f479f06722ad7df62d0371447e3191f08` | `d70be7e16af1d58ec04d9bed54cac522893a0ebc` |
| Phase 2 freeze | `17350c67948600677ccc92c41769b198d7037b6c` | `9b3e2a2539cdd6b1a417c7e92ae619ad7da654aa` |
| Phase 3 freeze | `f83211062abbd444be8b830790df689cc0729237` | `ede8c470c3c5966ada350ad0df4020f2f0505afc` |
| Phase 4 freeze | `f9910c31dad21cef10e02be20144ac4a07fd1b3b` | `39f7edcb1286217a3e28e57e695478e6fa56fc99` |
| Phase 5 freeze | `b888198ff0af2e69da6d4d97822697f07c3839d8` | `70b9c6d70106109d57b6de17722e8ecdf0b92f98` |
| Phase 6 freeze | `124be795cedf25f275f4cf7c63885e0c3e58d0c1` | `f0d92474c67d07dd969b817f429a75b6845c545f` |
| Phase 7 freeze | `f8f4e856e0c304d08f95bd29bde13be8396d2141` | `1ed104a6364ccc455876b839c99846cae1a34b24` |
| Phase 8 freeze | `8daa691de8d481a3e7af7181a7281b3e8e0b5ea0` | `5231cbb0fc54d7c35dc4454b50764bfea8d3770a` |
| Phase 9 freeze | `58c6758632e7468e5e632e05457f6c9e318ba3b9` | `4eb9aa30f068b9429431b9ba54dea674e26799b1` |
| Phase 10 freeze | `ddab38b1268266df22924a7680fa717415f0235d` | `a16685db44cee99d02ed9d6642f6f415c1150f42` |
| Phase 11 authorization | `bbeb4c3d430ed02e6253490abed811de16b91404` | `b9d33737077e6f40a334608824246214d80654fc` |
| Phase 11 freeze | `5a729d1787f50ab83175266c0619cd905f1c3b43` | `b19a8ddf98465c5d9ce0e0ebf525354c955f4b15` |
| Phase 11 governance follow-up | `9bbaceca865767eed8d3e7abcf0d733363f4cc98` | `79a6554a9ccd64f39d9613e677603f9819754d3a` |
| Phase 11 verification follow-up | `c6a4b9ca29e503bacbfbbf65d0b480b0be79a05f` | `c72c9fb9dce4283d40a210c66279363fc3d7bfc1` |
| Phase 12 freeze | `82668b7e3262e9783e6ab58cb8037a1dc77e8e06` | `2835c3455c5a0e0fe4258da64df355e2f8e7da45` |
| Volume II freeze | `2a8c7fcaab60f7ca5d781ac81d838262da99894e` | `a33b097ae793a599b27e3d53cad4a1b534bdf395` |
| Volume III freeze | `4e5fa3c3843591ed59c0a87721c138484b8feefa` | `f168e29e7441f99cc22e064cd5fafcc4ac1bb9e4` |
| Volume IV design freeze | `15cfcece9207b8cd6f36f8b66c6ad2764ff4aeda` | `25b4c639814854413c7eafd1ce21f81b34b6f2ce` |
| Volume IV freeze | `1961d3a488aa1488c598eb51fb1e3a587bf8345f` | `b60c4e504a2965dfa5d24c15dbab29722b9c3863` |
| Volume IV governance follow-up | `74e7c9404ee945e34ace153bc935d6376877d9d5` | `59a6194ba0f6db7bcb245bdc06f0293f015aac33` |
| Volume V design freeze | `83590e6282bb24232bfe3099dd17053835a3fdf9` | `44f3680018f8d49ee9a652cfe1791a1eca7473bf` |
| Volume V governance follow-up | `5bf2990d1e8ae0c97baec52be0ce59fd4bbd195f` | `5510c6bb69efa02ba313bb848942472907875404` |
| Canonical Phase 13 Java verification baseline | `5d252bc66881ea7fbfee4d8d81b0f2731205e1bf` | `c9bc4a281d1702288ad3dffbd77d491155b24ddb` |
| Phase 13 freeze | `8d4c88b4ba5a2ffaece103baa453205e480019fa` | `57b949ab8c5fa28e95243e83df0910731dbec800` |
| Phase 13 governance follow-up | `6714ab5c71592fc2909f713145bcf55f60bf1374` | `809ff8e8c5ba79c5c8ceb9ec7d0e7b6985f3619f` |
| Phase 13 final documentation/retro HEAD | `febe68c6fbe4476816d1dabf72523004e76fd5f9` | `a11832ff563eacef74b2ceae4743639622cffb28` |

The sanitized acceptance record preserves the canonical Java 25 result and remediation chronology. No Phase 14 implementation was part of this history rewrite.

## Recreated annotated tags

| Tag | Sanitized target commit |
|---|---|
| `phase-1-freeze` | `d70be7e16af1d58ec04d9bed54cac522893a0ebc` |
| `phase-2-freeze` | `9b3e2a2539cdd6b1a417c7e92ae619ad7da654aa` |
| `phase-3-freeze` | `ede8c470c3c5966ada350ad0df4020f2f0505afc` |
| `phase-4-freeze` | `39f7edcb1286217a3e28e57e695478e6fa56fc99` |
| `phase-5-freeze` | `70b9c6d70106109d57b6de17722e8ecdf0b92f98` |
| `phase-6-freeze` | `f0d92474c67d07dd969b817f429a75b6845c545f` |
| `phase-7-freeze` | `1ed104a6364ccc455876b839c99846cae1a34b24` |
| `phase-8-freeze` | `5231cbb0fc54d7c35dc4454b50764bfea8d3770a` |
| `phase-9-freeze` | `4eb9aa30f068b9429431b9ba54dea674e26799b1` |
| `phase-10-freeze` | `a16685db44cee99d02ed9d6642f6f415c1150f42` |
| `phase-11-freeze` | `b19a8ddf98465c5d9ce0e0ebf525354c955f4b15` |
| `phase-12-freeze` | `2835c3455c5a0e0fe4258da64df355e2f8e7da45` |
| `phase-13-freeze` | `57b949ab8c5fa28e95243e83df0910731dbec800` |
| `volume-2-freeze` | `a33b097ae793a599b27e3d53cad4a1b534bdf395` |
| `volume-3-freeze` | `f168e29e7441f99cc22e064cd5fafcc4ac1bb9e4` |
| `volume-4-design-freeze` | `25b4c639814854413c7eafd1ce21f81b34b6f2ce` |
| `volume-4-freeze` | `b60c4e504a2965dfa5d24c15dbab29722b9c3863` |
| `volume-5-design-freeze` | `44f3680018f8d49ee9a652cfe1791a1eca7473bf` |

## Sanitized history audit

The final pre-publication audit enumerated all reachable commits, historical paths, and blob contents. It found zero historical `.scratch/**` paths and zero tracked build/cache directories, local runtime databases, log files, local JDK/toolchain archives, or other matched temporary-artifact paths. The root `.gitignore` rule `/.scratch/` was verified with `git check-ignore`.

A local content scan found no GitHub/AWS token-shaped credential, no user-specific Windows or POSIX home path, and no usable PEM private-key block. Its generic credential-keyword heuristic produced code-symbol and test-fixture matches; every string assignment hit was in synthetic integration tests and included an explicit test/demo/synthetic marker. The sole PEM marker is a header-only malformed input in `PartnerTrustConfigurationTest`, with no private-key body or matching end marker, used to verify rejection. The tracked `src/test/resources/exchange/synthetic-partner.p12` remains a named synthetic test fixture. These test assets were retained as legitimate test material; no production credentials were found.

`git fsck --full --strict --no-reflogs` passed after filtering. The mapped Phase 13 freeze and governance commits, Volume IV freeze, and Volume V design freeze are ancestors of the sanitized branch tip. The original unsanitized repository backup remains separate and unmodified.