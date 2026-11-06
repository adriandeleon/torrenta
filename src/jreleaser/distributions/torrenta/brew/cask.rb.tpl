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

  zap trash: [
    "~/.torrenta",
  ]
end
