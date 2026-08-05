# APK body/size 对照
Measured from real package files (not estimates).

| Role | applicationId | versionName | versionCode | size_bytes | size_MiB | path |
|------|---------------|-------------|-------------|------------|----------|------|
| original-debug | `com.lezi.babylog.debug` | `0.3.7-debug` | 14 | 29194777 | 27.84 | `/home/zhangtianshu/lezi/app/build/outputs/apk/debug/app-debug.apk` |
| original-release | `com.lezi.babylog` | `0.3.7` | 14 | 5783380 | 5.52 | `/home/zhangtianshu/lezi/app/build/outputs/apk/release/app-release.apk` |
| original-dist-0.3.7 | `com.lezi.babylog` | `0.3.7` | 14 | 5783395 | 5.52 | `/home/zhangtianshu/lezi/dist/lezi-0.3.7-release.apk` |
| greenfield-debug | `com.lezi.babylog.gf` | `1.0.0` | 100 | 23326126 | 22.25 | `/home/zhangtianshu/lezi-greenfield/greenfield/android/app/build/outputs/apk/debug/app-debug.apk` |

## Identity asserts

- Greenfield: `com.lezi.babylog.gf` + `1.0.0` required.
- Original production id: `com.lezi.babylog` (debug may use `.debug` suffix).
- Side-by-side install proven: see dual-packages.txt.

## DEX notes

### original-debug
```
 42561184  1981-01-01 01:01   classes.dex
   233168  1981-01-01 01:01   classes10.dex
  1519916  1981-01-01 01:01   classes12.dex
   507280  1981-01-01 01:01   classes14.dex
   267512  1981-01-01 01:01   classes15.dex
   594772  1981-01-01 01:01   classes17.dex
     2344  1981-01-01 01:01   classes18.dex
      976  1981-01-01 01:01   classes20.dex
 14217348  1981-01-01 01:01   classes21.dex
  9055836  1981-01-01 01:01   classes22.dex
  2831704  1981-01-01 01:01   classes23.dex
   408664  1981-01-01 01:01   classes3.dex
   286988  1981-01-01 01:01   classes6.dex
   411460  1981-01-01 01:01   classes7.dex
   482540  1981-01-01 01:01   classes9.dex
```

### greenfield-debug
```
 41685064  1981-01-01 01:01   classes.dex
 13369328  1981-01-01 01:01   classes12.dex
 10755860  1981-01-01 01:01   classes13.dex
   171140  1981-01-01 01:01   classes2.dex
    49636  1981-01-01 01:01   classes3.dex
    21896  1981-01-01 01:01   classes4.dex
   120112  1981-01-01 01:01   classes5.dex
    23128  1981-01-01 01:01   classes6.dex
   581044  1981-01-01 01:01   classes7.dex
     3896  1981-01-01 01:01   classes9.dex
   300848  1981-01-01 01:01   classes10.dex
    36676  1981-01-01 01:01   classes11.dex
    19388  1981-01-01 01:01   classes14.dex
     7780  1981-01-01 01:01   classes15.dex
    31968  1981-01-01 01:01   classes8.dex
```

