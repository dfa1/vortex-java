package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Zone;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorePageTest {

    @Nested
    class Render {

        @Test
        void isSelfContainedAndSaysNothingAboutStorage() {
            // Given
            ColumnProfile id = new ColumnProfile("id", DType.I64, 1000, 0L, 0L, 1L, 999L, null, List.of());

            // When
            String result = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(id));

            // Then — a business user sees rows and columns, not segments and encodings; the one
            // script is inline, nothing is fetched from elsewhere
            assertThat(result)
                    .startsWith("<!doctype html>")
                    .endsWith("</html>\n")
                    .contains("<title>data.vortex - Vortex explorer</title>")
                    .contains("1,000")
                    .doesNotContain("<script src")
                    .doesNotContain("http://")
                    .doesNotContain("https://")
                    .doesNotContain("Segments")
                    .doesNotContain("class=\"strip\"");
        }

        @Test
        void everyColumnGetsAnAnalyzeButtonByIndex() {
            // Given — the button names the column by position, never by its (untrusted) name
            ColumnProfile a = new ColumnProfile("a", DType.I64, 1, 0L, 0L, 1L, 1L, null, List.of());
            ColumnProfile b = new ColumnProfile("b", DType.I64, 1, 0L, 0L, 1L, 1L, null, List.of());

            // When
            String result = ExplorePage.render("data.vortex", 0L, 1L, List.of(a, b));

            // Then
            assertThat(result)
                    .contains("<button data-column=\"0\">Analyze</button>")
                    .contains("<button data-column=\"1\">Analyze</button>")
                    .contains("id=\"analyze-all\"");
        }

        @Test
        void publishedPageCarriesEachAnalysisUnderItsColumn() {
            // Given
            ColumnProfile a = new ColumnProfile("a", DType.I64, 1, 0L, 0L, 1L, 1L, null, List.of());
            ColumnProfile b = new ColumnProfile("b", DType.I64, 1, 0L, 0L, 1L, 1L, null, List.of());

            // When
            String result = ExplorePage.render("data.vortex", 0L, 1L, List.of(a, b), List.of("<p>A</p>", "<p>B</p>"));

            // Then — in column order, toggled like the live page after an analysis; nothing to fetch
            assertThat(result)
                    .contains("every column analyzed")
                    .doesNotContain("id=\"analyze-all\"")
                    .doesNotContain(">Analyze</button>")
                    .contains("<button data-column=\"0\" data-state=\"done\">Hide</button></td></tr>\n"
                            + "<tr class=\"detail\"><td colspan=\"6\"><p>A</p></td></tr>")
                    .contains("<tr class=\"detail\"><td colspan=\"6\"><p>B</p></td></tr>");
        }

        @Test
        void publishedPageNeedsOneAnalysisPerColumn() {
            // Given
            ColumnProfile a = new ColumnProfile("a", DType.I64, 1, 0L, 0L, 1L, 1L, null, List.of());

            // When / Then — a missing analysis would shift every later one under the wrong column
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            ExplorePage.render("data.vortex", 0L, 1L, List.of(a), List.of("<p>A</p>", "<p>B</p>")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("2 analyses for 1 columns");
        }

        @Test
        void profilesShowNullShareAndRange() {
            // Given — 250 of 1000 rows null
            ColumnProfile id = new ColumnProfile("id", DType.I64, 1000, 250L, 0L, 1L, 999L, null, List.of());

            // When
            String result = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(id));

            // Then
            assertThat(result)
                    .contains("card profiles")
                    .contains("25.0%")
                    .contains("<code>1</code>")
                    .contains("<code>999</code>");
        }

        @Test
        void meanColumnOnlyWhenSomeColumnHasASum() {
            // Given — Rust's zone map records no sum, so most files have no mean at all: a column of
            // dashes would only be noise
            ColumnProfile noSum = new ColumnProfile("id", DType.I64, 4, 0L, 0L, 1L, 4L, null, List.of());
            ColumnProfile withSum = new ColumnProfile("id", DType.I64, 4, 0L, 0L, 1L, 4L, 10L, List.of());

            // When
            String without = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(noSum));
            String result = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(withSum));

            // Then
            assertThat(without).doesNotContain("<th>mean</th>");
            assertThat(result).contains("<th>mean</th>").contains("<code>2.50</code>");
        }

        @Test
        void boolMeanReadsAsShareOfTrue() {
            // Given — a bool column's sum is its true count
            ColumnProfile flag = new ColumnProfile("flag", new DType.Bool(false), 4, 0L, 0L, false, true, 1L, List.of());

            // When
            String result = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(flag));

            // Then
            assertThat(result).contains("25.0% true");
        }

        @Test
        void sparklineNeedsAtLeastTwoZones() {
            // Given — one zone is one band: no shape over the rows to show
            ArrayStats a = new ArrayStats(0L, true, 9L, true, null, null, null, null, 0L, null, null);
            ArrayStats b = new ArrayStats(10L, true, 19L, true, null, null, null, null, 0L, null, null);
            ColumnProfile one = new ColumnProfile("id", DType.I64, 10, 0L, 0L, 0L, 9L, null,
                    List.of(new Zone(0, 10, a)));
            ColumnProfile two = new ColumnProfile("id", DType.I64, 20, 0L, 0L, 0L, 19L, null,
                    List.of(new Zone(0, 10, a), new Zone(10, 10, b)));

            // When
            String single = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(one));
            String result = ExplorePage.render("data.vortex", 4096L, 1000L, List.of(two));

            // Then — the band rises from the bottom-left to the top-right: a sorted column
            assertThat(single).doesNotContain("class=\"spark\"");
            assertThat(result).contains("class=\"spark\"")
                    .contains("points=\"0.00,12.53 100.00,2.00 100.00,11.47 0.00,22.00\"");
        }

        @Test
        void escapesUntrustedProfileValues() {
            // Given — a profile's name and string min/max come off the wire
            ColumnProfile evil = new ColumnProfile("<i>", DType.UTF8, 1, 0L, 0L, "<b>", "<u>", null, List.of());

            // When
            String result = ExplorePage.render("evil.vortex", 4096L, 1000L, List.of(evil));

            // Then
            assertThat(result).doesNotContain("<i>").doesNotContain("<b>").doesNotContain("<u>");
        }
    }

    @Nested
    class Distribution {

        private static final ColumnProfile PRICE =
                new ColumnProfile("price", DType.F64, 10, 0L, 0L, 0.0, 10.0, null, List.of());

        @Test
        void numericColumnShowsTotalMeanAndHistogram() {
            // Given — 10 rows over two bins
            ColumnDistribution dist = new ColumnDistribution(10, 0, 50.0, null, List.of(
                    new ColumnDistribution.Bar(0.0, 5.0, 6),
                    new ColumnDistribution.Bar(5.0, 10.0, 4)), true, null);

            // When
            String result = ExplorePage.distribution(PRICE, dist);

            // Then
            assertThat(result)
                    .contains("10 values")
                    .contains("total <code>50</code>")
                    .contains("mean <code>5</code>")
                    .contains("more than 10,000 distinct")
                    .contains("class=\"hist\"")
                    .contains("<title>0 to 5: 6 rows</title>");
        }

        @Test
        void fewDistinctValuesShowEachWithCountAndShare() {
            // Given — 2 of 8 rows null: shares are of the 6 present values
            ColumnProfile country = new ColumnProfile("country", DType.UTF8, 8, 2L, 0L, "IT", "US", null, List.of());
            ColumnDistribution dist = new ColumnDistribution(8, 2, null, 2L, List.of(
                    new ColumnDistribution.Bar("IT", null, 4),
                    new ColumnDistribution.Bar("US", null, 2)), false, new ColumnDistribution.Lengths(2, 2, 12));

            // When
            String result = ExplorePage.distribution(country, dist);

            // Then
            assertThat(result)
                    .contains("6 values")
                    .contains("2 null (25.0%)")
                    .contains("2 distinct")
                    .contains("2-2 bytes long, 2 on average")
                    .contains("<code>IT</code>")
                    .contains("66.7%")
                    .doesNotContain("most frequent");
        }

        @Test
        void topValuesAreLabeledAsSuch() {
            // Given — 500 distinct values, only the most frequent shown
            ColumnProfile name = new ColumnProfile("name", DType.UTF8, 1000, 0L, 0L, null, null, null, List.of());
            ColumnDistribution dist = new ColumnDistribution(1000, 0, null, 500L,
                    List.of(new ColumnDistribution.Bar("bob", null, 9)), false, null);

            // When
            String result = ExplorePage.distribution(name, dist);

            // Then
            assertThat(result).contains("most frequent 1:");
        }

        @Test
        void escapesUntrustedValues() {
            // Given — counted values are strings straight from the file
            ColumnProfile s = new ColumnProfile("s", DType.UTF8, 1, 0L, 0L, null, null, null, List.of());
            ColumnDistribution dist = new ColumnDistribution(1, 0, null, 1L,
                    List.of(new ColumnDistribution.Bar("<img src=x onerror=alert(1)>", null, 1)), false, null);

            // When
            String result = ExplorePage.distribution(s, dist);

            // Then
            assertThat(result).doesNotContain("<img").contains("&lt;img");
        }
    }
}
