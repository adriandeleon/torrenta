package com.torrenta.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the Linux signing chain (docs/release.md, "Linux signing"). It runs only in a release, and
 * each mistake it guards against ships quietly: a publish that does not wait for the signed files, a
 * tag that releases unsigned packages, or a signature that is made but never attached.
 */
class LinuxSigningTest {

    private static final String[] FORMATS = {"deb", "rpm"};

    @Test
    void aTagCannotPublishUnsignedLinuxPackages() throws IOException {
        String release =
                Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
        int build = release.indexOf("\n  build:\n");
        int job = release.indexOf("\n  sign-linux:\n");
        int publish = release.indexOf("\n  release:\n");
        assertTrue(job >= 0 && job < publish, "the release no longer signs the Linux packages");
        assertFalse(
                release.substring(build, job).contains("LINUX_GPG_KEY"),
                "the key must never reach a build leg, which runs whatever Maven resolves");
        String sign = release.substring(job, publish);
        assertTrue(
                sign.contains("GITHUB_REF_TYPE\" = \"tag\"") && sign.contains("exit 1"),
                "a tag without the signing key must fail");
        assertTrue(
                sign.contains("TORRENTA_GPG_EXPECTED_KEY=packaging/linux/signing-key.asc"),
                "a tag must be signed by the committed key and no other");
        assertTrue(
                sign.indexOf("sign-linux-packages.sh") < sign.indexOf("check-linux-signatures.sh"),
                "what was signed is checked against the public key before it is uploaded");
        assertTrue(
                sign.contains("name: Torrenta-linux-x64\n          path: linux/*\n          overwrite: true"),
                "the signed files must replace the unsigned ones");
        assertTrue(
                release.substring(publish).contains("needs: [preflight, build, sign-linux]"),
                "the publish job must wait for the signed files");
    }

    @Test
    void everyLinuxDownloadIsAttachedWithItsSignature() throws IOException {
        String jreleaser = Files.readString(Path.of("jreleaser.yml"));
        for (String format : FORMATS) {
            String file = "artifacts/Torrenta-{{projectVersion}}-linux-x64." + format;
            assertTrue(jreleaser.contains("'" + file + "'"), file + " is not attached");
            assertTrue(jreleaser.contains("'" + file + ".asc'"), file + " is attached without its signature");
        }
        assertTrue(jreleaser.contains("'artifacts/torrenta-signing-key.asc'"), "the public key is not attached");
    }

    @Test
    void theSigningScriptSignsEveryFormatItsCheckExpects() throws IOException {
        String sign = Files.readString(Path.of("scripts", "sign-linux-packages.sh"));
        String check = Files.readString(Path.of("scripts", "check-linux-signatures.sh"));
        for (String format : FORMATS) {
            assertTrue(sign.contains("\"$dir\"/*." + format), "sign-linux-packages.sh does not sign ." + format);
            assertTrue(check.contains("\"$dir\"/*." + format), "check-linux-signatures.sh does not check ." + format);
        }
        assertTrue(
                sign.indexOf("rpmsign --addsign") < sign.indexOf("--armor --detach-sign")
                        && sign.indexOf("sign_deb \"$deb\"") < sign.indexOf("--armor --detach-sign"),
                "the embedded signatures change the files, so the detached ones come after");
    }
}
