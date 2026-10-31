package com.torrenta.os;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the macOS Developer ID signing chain (docs/release.md). None of it runs off a Mac or outside
 * a release, and each mistake it guards against ships quietly: an app signed before a later step edits
 * it, a dmg wrap that re-signs it ad hoc, or a tag that publishes an unsigned dmg.
 */
class MacSigningTest {

    @Test
    void theAppIsSignedAfterItsLastEditAndBeforeTheInstallersWrapIt() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        int types = pom.indexOf("<id>add-mac-types</id>");
        int sign = pom.indexOf("<id>sign-mac-app</id>");
        int installers = pom.indexOf("<id>build-installers</id>");
        assertTrue(sign >= 0, "the dist profile no longer signs the app");
        assertTrue(
                pom.indexOf("<id>fix-mac-bundle-version</id>") < sign && types < sign && sign < installers,
                "both Info.plist edits must come before signing (an edit breaks the seal), and the dmg must wrap"
                        + " the signed app");
    }

    @Test
    void theSigningScriptKeepsItsSignatureThroughTheDmgWrap() throws IOException {
        String script = Files.readString(Path.of("scripts", "sign-mac-app.sh"));
        assertTrue(script.contains("<signed>true</signed>"), "jpackage re-signs an image it believes unsigned, ad hoc");
        assertTrue(script.indexOf(".jpackage.xml") < script.indexOf("xattr -cr"), "the flag is sealed: set it first");
        assertTrue(
                script.contains("--options runtime") && script.contains("--timestamp"),
                "notarization requires the hardened runtime and a secure timestamp");
        assertTrue(Files.isRegularFile(Path.of("packaging", "macos", "Torrenta.entitlements")));
        assertTrue(
                Files.readString(Path.of("scripts", "build-installers.sh")).contains("TORRENTA_MAC_SIGN_IDENTITY"),
                "the dmg gets its own signature");
    }

    @Test
    void aTagCannotPublishAnUnsignedOrUnnotarizedDmg() throws IOException {
        String release =
                Files.readString(Path.of(".github", "workflows", "release.yml")).replace("\r\n", "\n");
        int importCert = release.indexOf("- name: Import signing certificate (macOS)");
        int pkg = release.indexOf("- name: Package");
        int smoke = release.indexOf("- name: Smoke-test the packaged app");
        int notarize = release.indexOf("- name: Notarize the dmg (macOS)");
        int upload = release.indexOf("- name: Upload\n");
        assertTrue(importCert >= 0 && notarize >= 0, "the macOS leg no longer signs and notarizes");
        assertTrue(
                importCert < pkg && pkg < smoke && smoke < notarize && notarize < upload,
                "the keychain exists before Package signs, the smoke test runs the signed app, and the dmg is"
                        + " notarized before it is uploaded");
        String importStep = release.substring(importCert, pkg);
        assertTrue(
                importStep.contains("GITHUB_REF_TYPE\" = \"tag\"") && importStep.contains("exit 1"),
                "a tag without the signing secrets must fail");
        String notarizeStep = release.substring(notarize, upload);
        assertTrue(notarizeStep.contains("stapler staple") && notarizeStep.contains("spctl --assess"));
        assertTrue(
                release.contains("if: always() && steps.macsign.outputs.enabled == 'true'")
                        && release.contains("security delete-keychain"),
                "the keychain holding the private key is removed even when the leg fails");
    }
}
