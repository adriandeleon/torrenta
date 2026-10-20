package com.torrenta.update;

/**
 * A published release.
 *
 * @param version the tag with any leading {@code v} stripped, so it compares against
 *     {@code AppInfo.VERSION}
 * @param url the release page
 * @param name the release title, which may be blank
 */
public record ReleaseInfo(String version, String url, String name) {}
