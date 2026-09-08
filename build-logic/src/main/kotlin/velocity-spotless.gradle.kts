import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin

apply<SpotlessPlugin>()

// Sources authored by Neko Network rather than inherited from upstream Velocity.
// These carry HEADER-NEKO.txt; everything else keeps the upstream HEADER.txt so that
// merges from upstream/dev never conflict on a copyright line.
val nekoSources = arrayOf(
    "src/*/java/com/velocitypowered/proxy/network/discovery/**/*.java",
    "src/*/java/com/velocitypowered/proxy/command/builtin/RegionCommand.java"
)

extensions.configure<SpotlessExtension> {
    java {
        if (project.name == "velocity-api") {
            licenseHeaderFile(file("HEADER.txt"))
            targetExclude("**/java/com/velocitypowered/api/util/Ordered.java")
        } else {
            licenseHeaderFile(rootProject.file("HEADER.txt"))
            targetExclude(*nekoSources)
        }
        removeUnusedImports()
    }
    if (project.name == "velocity-proxy") {
        format("nekoLicense") {
            target(*nekoSources)
            licenseHeaderFile(rootProject.file("HEADER-NEKO.txt"), "package ")
        }
    }
}
