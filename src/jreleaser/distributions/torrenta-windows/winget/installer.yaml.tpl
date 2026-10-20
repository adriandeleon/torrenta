# {{jreleaserCreationStamp}}
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.9.0.schema.json

# Hand-written rather than JReleaser's stock template, which emitted empty Scope, InstallModes and
# UpgradeBehavior keys — empty is not the same as absent to the schema validator.
#
# An MSI needs no NestedInstallerType or NestedInstallerFiles; those belong to the zip form this
# replaced. ProductCode is deliberately omitted: winget derives it from the package itself, and a
# wrong one makes upgrades and uninstalls silently fail to find what they installed.
PackageIdentifier: {{wingetPackageIdentifier}}
PackageVersion: {{wingetPackageVersion}}
MinimumOSVersion: {{wingetMinimumOsVersion}}
InstallerType: msi
Scope: user
UpgradeBehavior: install
ReleaseDate: {{wingetReleaseDate}}
Installers:
  - Architecture: {{wingetInstallerArchitecture}}
    InstallerUrl: {{distributionUrl}}
    InstallerSha256: {{distributionChecksumSha256}}
ManifestType: installer
ManifestVersion: 1.9.0
