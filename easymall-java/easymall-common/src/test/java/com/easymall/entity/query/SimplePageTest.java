package com.easymall.entity.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimplePageTest {

    @Test
    void missingPageStartsAtTheFirstPage() {
        SimplePage page = new SimplePage(null, 21, 10);

        assertThat(page.getPageNo()).isEqualTo(1);
        assertThat(page.getPageTotal()).isEqualTo(3);
        assertThat(page.getStart()).isZero();
        assertThat(page.getEnd()).isEqualTo(10);
    }

    @Test
    void pageBeyondTheResultSetUsesTheLastPageOffset() {
        SimplePage page = new SimplePage(99, 21, 10);

        assertThat(page.getPageNo()).isEqualTo(3);
        assertThat(page.getStart()).isEqualTo(20);
        assertThat(page.getEnd()).isEqualTo(10);
    }

    @Test
    void emptyResultsStillHaveANonNegativeFirstPageOffset() {
        SimplePage page = new SimplePage(3, 0, 10);

        assertThat(page.getCountTotal()).isZero();
        assertThat(page.getPageNo()).isEqualTo(1);
        assertThat(page.getStart()).isZero();
    }

    @Test
    void invalidPageSizeUsesTheExistingDefaultOfTwenty() {
        SimplePage page = new SimplePage(2, 21, 0);

        assertThat(page.getPageSize()).isEqualTo(20);
        assertThat(page.getPageTotal()).isEqualTo(2);
        assertThat(page.getStart()).isEqualTo(20);
    }
}
