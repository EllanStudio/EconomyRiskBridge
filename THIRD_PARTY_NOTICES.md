# Third-Party Notices

EconomyRiskBridge's shadow JAR contains and relocates the components listed
below. The project-level `LICENSE` applies to EconomyRiskBridge as a combined
work. The original third-party terms and notices remain applicable to their
respective components.

## Sparrow Reflection 0.27

- Project: https://github.com/Xiao-MoMi/sparrow-reflection
- License: GNU General Public License version 3 only (GPL-3.0-only)
- Binary: https://repo.momirealms.net/releases/net/momirealms/sparrow-reflection/0.27/sparrow-reflection-0.27.jar
- Corresponding source: https://repo.momirealms.net/releases/net/momirealms/sparrow-reflection/0.27/sparrow-reflection-0.27-sources.jar

The build relocates Sparrow Reflection from
`net.momirealms.sparrow.reflection` to
`cn.superiormc.economyriskbridge.lib.sparrow`. This packaging modification is
performed by EconomyRiskBridge's Gradle build and was introduced on
2026-08-11. The complete GPLv3 text is included in the project-level
`LICENSE` and in the distributed JAR.

## ASM 9.9.1

- Project: https://asm.ow2.io/
- License: BSD-3-Clause
- Corresponding source: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.9.1/asm-9.9.1-sources.jar

The build relocates ASM from `org.objectweb.asm` to
`cn.superiormc.economyriskbridge.lib.asm`. The full copyright notice and
license are in `third-party/ASM-BSD-3-Clause.txt` and in the distributed JAR.

## Fabric Mapping IO 0.8.0

- Project: https://github.com/FabricMC/mapping-io
- License: Apache License 2.0
- Corresponding source: https://maven.fabricmc.net/net/fabricmc/mapping-io/0.8.0/mapping-io-0.8.0-sources.jar

Mapping IO is incorporated in the published Sparrow Reflection artifact and
is transitively relocated with it. The full Apache License 2.0 text is in
`third-party/Apache-2.0.txt` and in the distributed JAR.

## SQLite JDBC 3.50.3.0

- Project: https://github.com/xerial/sqlite-jdbc
- License: Apache License 2.0; bundled SQLite portions are public domain, with
  the upstream Zentus notice retained
- Corresponding source: https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0-sources.jar

The upstream `LICENSE` and `LICENSE.zentus` files remain at
`META-INF/maven/org.xerial/sqlite-jdbc/` in the distributed JAR. The full
Apache License 2.0 text is also included under `META-INF/licenses/`.

## Corresponding source for EconomyRiskBridge binaries

Every distributed EconomyRiskBridge JAR must identify the exact Git commit or
release tag from which it was built. The machine-readable source for that
exact revision, including the Gradle wrapper and build scripts, must remain
available at no additional charge from:

https://github.com/EllanServer/EconomyRiskBridge

The dependency source links above form part of these source-availability
directions. If a binary cannot be matched to an available source revision, it
must not be redistributed.
