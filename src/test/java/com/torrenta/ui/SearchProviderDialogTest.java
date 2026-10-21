package com.torrenta.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Test line's wording; the dialog itself is driven by the capture hook. */
class SearchProviderDialogTest {

    @Test
    void theTestLineNamesTheServerAndCountsItsCategories() {
        assertEquals("Prowlarr · 22 categories", SearchProviderDialog.categoriesLine("Prowlarr", 22));
        assertEquals("Jackett · 1 category", SearchProviderDialog.categoriesLine("Jackett", 1));
        assertEquals("0 categories", SearchProviderDialog.categoriesLine("", 0));
    }
}
