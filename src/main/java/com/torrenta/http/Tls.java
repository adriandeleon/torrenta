package com.torrenta.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * HTTPS from a PKCS#12 file (a {@code .p12}/{@code .pfx} holding the certificate and its key), the
 * one format every tool exports: {@code openssl pkcs12 -export}, {@code keytool}, a Let's Encrypt
 * client. TLS 1.2 and up, the JDK's own defaults otherwise.
 */
public final class Tls {

    private Tls() {}

    public static SSLContext fromPkcs12(Path file, char[] password) throws IOException, GeneralSecurityException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, password);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        return context;
    }
}
