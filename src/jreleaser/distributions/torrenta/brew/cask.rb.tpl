# {{jreleaserCreationStamp}}
{{! Overrides JReleaser's stock cask template. Why, and what changed, is in NOTES.md beside this
    file — kept out of here so the rationale does not end up in the cask every user reads. }}
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
