package dev.aiauto.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** A release is no use without its APK, so the Release workflow keeps one hidden until the APK is attached. */
class ReleaseWorkflowTest {
    // Gradle runs tests from the core module's directory.
    private val workflow = File("../.github/workflows/release.yml").readText()

    private fun at(text: String): Int = workflow.indexOf(text).also { assertTrue(it >= 0, "release.yml should contain: $text") }

    @Test fun draftsAReleaseWithoutAnApkBeforeBuilding() {
        val draft = at("gh release edit \"\$VERSION\" --draft=true")
        assertTrue(at("""select(endswith(".apk"))""") < draft, "Only a release without an APK should become a draft")
        assertTrue(draft < at("./gradlew"), "The release should be hidden before the build starts")
    }

    @Test fun publishesTheReleaseOnlyAfterAttachingTheApk() {
        val upload = at("gh release upload \"\$TAG\" \"cruise-control-\$TAG.apk\"")
        val publish = at("gh release edit \"\$TAG\" --draft=false")
        assertTrue(upload < publish, "The release should be published after its APK is attached")
    }
}
