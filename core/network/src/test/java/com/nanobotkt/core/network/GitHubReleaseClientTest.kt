package com.nanobotkt.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseClientTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `formal release parses tag body and universal apk`() {
        val release = GitHubReleaseParser.parse(
            json = json,
            raw = releaseJson(
                tag = "v0.1.5",
                body = "# NanobotKT 0.1.5\n\n- 更新设置页",
                assetName = "app-universal-release.apk",
            ),
            channel = GitHubReleaseChannel.RELEASE,
        )

        assertEquals("0.1.5", release.versionName)
        assertEquals("# NanobotKT 0.1.5\n\n- 更新设置页", release.body)
        assertEquals("app-universal-release.apk", release.asset.name)
        assertEquals(1024L, release.asset.sizeBytes)
    }

    @Test
    fun `release without universal apk is rejected`() {
        val error = assertThrows(GitHubReleaseException.ApkNotFound::class.java) {
            GitHubReleaseParser.parse(
                json = json,
                raw = releaseJson(
                    tag = "v0.1.5",
                    body = "# NanobotKT 0.1.5",
                    assetName = "app-arm64-v8a-release.apk",
                ),
                channel = GitHubReleaseChannel.RELEASE,
            )
        }

        assertEquals("universal_apk_not_found", error.message)
    }

    @Test
    fun `missing body is exposed as empty changelog`() {
        val release = GitHubReleaseParser.parse(
            json = json,
            raw = """
                {
                  "tag_name": "v0.1.5",
                  "name": "NanobotKT 0.1.5",
                  "assets": [
                    {
                      "name": "app-universal-release.apk",
                      "browser_download_url": "https://github.com/yaotutu/nanobotkt/releases/download/v0.1.5/app-universal-release.apk",
                      "size": 1024
                    }
                  ]
                }
            """.trimIndent(),
            channel = GitHubReleaseChannel.RELEASE,
        )

        assertEquals("", release.body)
    }

    @Test
    fun `missing tag is rejected instead of guessing a version`() {
        assertThrows(GitHubReleaseException.InvalidRelease::class.java) {
            GitHubReleaseParser.parse(
                json = json,
                raw = """
                    {
                      "body": "# NanobotKT 0.1.5",
                      "assets": [
                        {
                          "name": "app-universal-release.apk",
                          "browser_download_url": "https://github.com/yaotutu/nanobotkt/releases/download/v0.1.5/app-universal-release.apk"
                        }
                      ]
                    }
                """.trimIndent(),
                channel = GitHubReleaseChannel.RELEASE,
            )
        }
    }

    @Test
    fun `dev latest reads numeric version from changelog body`() {
        val release = GitHubReleaseParser.parse(
            json = json,
            raw = releaseJson(
                tag = "dev-latest",
                body = "# NanobotKT 0.1.6\n\n- Dev build",
                assetName = "app-universal-dev.apk",
                releaseName = "NanobotKT dev latest (0.1.5-dev)",
            ),
            channel = GitHubReleaseChannel.DEV,
        )

        // body 是当前发布流程的稳定版本来源，应优先于可能滞后的 Release title。
        assertEquals("0.1.6", release.versionName)
    }

    @Test
    fun `untrusted universal apk url is never exposed to downloader`() {
        val error = assertThrows(GitHubReleaseException.ApkNotFound::class.java) {
            GitHubReleaseParser.parse(
                json = json,
                raw = """
                    {
                      "tag_name": "v0.1.5",
                      "body": "# NanobotKT 0.1.5",
                      "assets": [
                        {
                          "name": "app-universal-release.apk",
                          "browser_download_url": "https://example.com/app-universal-release.apk",
                          "size": 1024
                        }
                      ]
                    }
                """.trimIndent(),
                channel = GitHubReleaseChannel.RELEASE,
            )
        }

        assertTrue(error is GitHubReleaseException.ApkNotFound)
    }

    private fun releaseJson(
        tag: String,
        body: String,
        assetName: String,
        releaseName: String = "NanobotKT release",
    ): String = """
        {
          "tag_name": "$tag",
          "name": "$releaseName",
          "body": ${json.encodeToString(kotlinx.serialization.serializer<String>(), body)},
          "prerelease": ${tag == "dev-latest"},
          "assets": [
            {
              "name": "$assetName",
              "browser_download_url": "https://github.com/yaotutu/nanobotkt/releases/download/$tag/$assetName",
              "size": 1024
            }
          ]
        }
    """.trimIndent()
}
