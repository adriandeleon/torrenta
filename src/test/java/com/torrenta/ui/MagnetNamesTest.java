package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MagnetNamesTest {

    @Test
    void aNameThatDoesNotDecodeIsShownAsWritten() {
        assertEquals("100%ZZ", MagnetNames.displayName("magnet:?xt=urn:btih:abc&dn=100%ZZ"));
        assertEquals("a b", MagnetNames.displayName("magnet:?dn=a+b"));
    }
}
