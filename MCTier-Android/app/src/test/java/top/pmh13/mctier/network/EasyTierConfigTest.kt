package top.pmh13.mctier.network

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EasyTierConfigTest {
    private fun config(enabled: Boolean) = buildEasyTierConfig(
        instanceName = "private-mode-test",
        networkName = "MCTier-test",
        networkSecret = "test-password",
        hostname = "test-player",
        peers = listOf("tcp://127.0.0.1:11010"),
        virtualIp = "10.126.126.42/24",
        privateMode = enabled,
    )

    @Test fun privateModeIsAnExplicitBooleanInFlags() {
        for (enabled in listOf(false, true)) {
            val text = config(enabled)
            val flags = text.substringAfter("[flags]\n").substringBefore("\n[")
            assertTrue(flags.lines().contains("private_mode = $enabled"))
            assertEquals(1, text.lines().count { it.startsWith("private_mode =") })
            assertTrue(text.contains("network_name = \"MCTier-test\""))
            assertTrue(text.contains("network_secret = \"test-password\""))
        }
    }

    @Test fun privateModeConfigurationsPassNativeParser() {
        // Optional on hosts without the desktop binary; set this in cross-platform checks.
        val core = System.getenv("MCTIER_EASYTIER_CORE")
        assumeTrue("Set MCTIER_EASYTIER_CORE to validate with EasyTier", !core.isNullOrBlank())
        assertTrue(File(core!!).isFile)
        for (enabled in listOf(false, true)) {
            val file = File.createTempFile("mctier-private-mode-", ".toml")
            try {
                file.writeText(config(enabled))
                val process = ProcessBuilder(core, "--check-config", "--config-file", file.absolutePath)
                    .redirectErrorStream(true).start()
                try {
                    assertTrue("EasyTier validation timed out", process.waitFor(15, TimeUnit.SECONDS))
                    val output = process.inputStream.bufferedReader().readText()
                    assertEquals("privateMode=$enabled: $output", 0, process.exitValue())
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            } finally {
                file.delete()
            }
        }
    }
}
