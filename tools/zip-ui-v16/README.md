# v16 ZIP UI asset contract

`manifest.json` is the single catalog for the 57 planned v16 screen assets. Each
drawable name follows `zip_screen_NN_state_name`: `NN` is the continuous screen
ID and the suffix is the stable Java enum name in lower snake case. No PNG is
generated or claimed by this directory.

The catalog uses only these fixed visual templates: `HOME`, `PROMPT`, `AUTH`,
`BIOMETRIC`, `LOCKER`, `ADMIN`, and `DETAIL`. `dynamicRegion` identifies the
native live area above a future ZIP baseboard, while `actions` records the
semantic callback every visible control must receive during UI integration.

`reference-map.json` records the extracted `img-01.png` through `img-37.png`
reference identifiers and the nearest permitted reference for every catalog
screen. It is a selection contract, not evidence that a generated asset exists.

Run the pure-JVM catalog check from the project root with the pinned manual
tooling:

```powershell
$jbr = 'C:\Users\Administrator\.jdks\jbr-21.0.11\bin'
$cp = 'manual-build\tooling\junit-4.13.2.jar;manual-build\tooling\hamcrest-core-1.3.jar'
& "$jbr\javac.exe" -encoding UTF-8 -source 8 -target 8 -cp $cp -d <classes> app\src\main\java\com\codex\lockertest\ui\zip\*.java app\src\test\java\com\codex\lockertest\ui\zip\ZipScreenCatalogTest.java
& "$jbr\java.exe" -cp "<classes>;$cp" org.junit.runner.JUnitCore com.codex.lockertest.ui.zip.ZipScreenCatalogTest
```
