# {{jreleaserCreationStamp}}
{{! Overrides JReleaser's stock cask template. Why, and what changed: src/jreleaser/NOTES.md. }}
cask "{{brewCaskName}}" do
  desc "{{projectDescription}}"
  homepage "{{projectLinkHomepage}}"
  url "{{distributionUrl}}",
      verified: "{{repoHost}}"
  version "{{projectVersion}}"
  sha256 "{{distributionChecksumSha256}}"
  name "{{brewCaskDisplayName}}"

  app "Torrenta.app"

  caveats <<~EOS
    Torrenta is not signed with an Apple Developer ID yet, so macOS will refuse to open it the
    first time. Allow it once under System Settings > Privacy & Security > Open Anyway.
  EOS

  zap trash: [
    "~/.torrenta",
  ]
end
