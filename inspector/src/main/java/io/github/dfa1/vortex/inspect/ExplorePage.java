package io.github.dfa1.vortex.inspect;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ArrayStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.dfa1.vortex.inspect.HtmlReport.appendHead;
import static io.github.dfa1.vortex.inspect.HtmlReport.bar;
import static io.github.dfa1.vortex.inspect.HtmlReport.count;
import static io.github.dfa1.vortex.inspect.HtmlReport.escape;
import static io.github.dfa1.vortex.inspect.HtmlReport.fill;
import static io.github.dfa1.vortex.inspect.HtmlReport.percent;
import static io.github.dfa1.vortex.inspect.HtmlReport.readable;
import static io.github.dfa1.vortex.inspect.HtmlReport.stat;
import static io.github.dfa1.vortex.inspect.HtmlReport.value;

/// The data-first page, for people who ask what is in a file rather than how it is stored:
/// per-column statistics and nothing about encodings, segments or bytes. [HtmlReport] is the
/// storage-first one.
///
/// The page opens on the statistics the file records ([ColumnProfile]); each column's
/// distribution ([ColumnDistribution]) needs a pass over its values. Served live
/// ([#render(String, long, long, List)]), the page fetches it on demand from `column/<index>`
/// relative to its own URL; published ([#render(String, long, long, List, List)]), every column's is
/// already in the page. Either way a distribution is the HTML fragment
/// [#distribution(ColumnProfile, ColumnDistribution)] renders, and every value in it is escaped here,
/// as on the page. The page's only script fetches fragments and shows or hides them.
public final class ExplorePage {

    private ExplorePage() {
    }

    /// Renders the live page, which asks its server for each column's distribution on demand.
    ///
    /// @param title    file name shown in the header; escaped before it reaches the page
    /// @param fileSize size of the file in bytes
    /// @param rows     rows in the file
    /// @param profiles per-column statistics, from [ColumnProfile#of(io.github.dfa1.vortex.reader.VortexHandle)]
    /// @return a complete HTML document
    public static String render(String title, long fileSize, long rows, List<ColumnProfile> profiles) {
        return render(title, fileSize, rows, profiles, List.of());
    }

    /// Renders the page with every column's distribution already in it: one file that needs no
    /// server, to publish or send.
    ///
    /// @param title    file name shown in the header; escaped before it reaches the page
    /// @param fileSize size of the file in bytes
    /// @param rows     rows in the file
    /// @param profiles per-column statistics, from [ColumnProfile#of(io.github.dfa1.vortex.reader.VortexHandle)]
    /// @param analyses each column's fragment from [#distribution(ColumnProfile, ColumnDistribution)], in
    ///                 column order; empty for the live page
    /// @return a complete HTML document
    public static String render(String title, long fileSize, long rows, List<ColumnProfile> profiles,
                                List<String> analyses) {
        if (!analyses.isEmpty() && analyses.size() != profiles.size()) {
            throw new IllegalArgumentException(analyses.size() + " analyses for " + profiles.size() + " columns");
        }
        StringBuilder sb = new StringBuilder(16 * 1024);
        appendHead(sb, title + " - Vortex explorer");
        sb.append("<section class=\"card\">\n<h1><code>").append(escape(title)).append("</code></h1>\n<dl class=\"stats\">\n");
        stat(sb, "Rows", count(rows));
        stat(sb, "Columns", count(profiles.size()));
        stat(sb, "Size", ByteSize.format(fileSize));
        sb.append("</dl>\n</section>\n");
        appendProfiles(sb, profiles, analyses);
        sb.append("<script>\n").append(SCRIPT).append("</script>\n</main>\n</body>\n</html>\n");
        return sb.toString();
    }

    /// Renders one column's distribution as the fragment the page inserts under the column's row.
    ///
    /// @param profile      the column's recorded statistics
    /// @param distribution the column's distribution, from [ColumnDistribution#of(io.github.dfa1.vortex.reader.VortexHandle, ColumnProfile)]
    /// @return an HTML fragment
    public static String distribution(ColumnProfile profile, ColumnDistribution distribution) {
        StringBuilder sb = new StringBuilder(4 * 1024);
        DType type = profile.type();
        List<String> facts = new ArrayList<>();
        facts.add(count(distribution.rows() - distribution.nulls()) + " values");
        facts.add(count(distribution.nulls()) + " null (" + percent(distribution.nulls(), distribution.rows()) + ")");
        if (distribution.sum() != null) {
            facts.add("total <code>" + number(distribution.sum().doubleValue()) + "</code>");
        }
        if (distribution.mean() != null) {
            facts.add("mean <code>" + number(distribution.mean()) + "</code>");
        }
        if (distribution.distinct() == null) {
            facts.add("more than " + count(ColumnDistribution.MAX_DISTINCT) + " distinct");
        } else {
            facts.add(count(distribution.distinct()) + " distinct");
        }
        ColumnDistribution.Lengths lengths = distribution.lengths();
        if (lengths != null) {
            long present = distribution.rows() - distribution.nulls();
            facts.add(count(lengths.min()) + "-" + count(lengths.max()) + " bytes long, "
                    + number((double) lengths.total() / present) + " on average");
        }
        sb.append("<div class=\"dist\">\n<p class=\"meta\">").append(String.join(" · ", facts)).append("</p>\n");
        List<ColumnDistribution.Bar> bars = distribution.bars();
        if (distribution.histogram()) {
            appendHistogram(sb, bars, type);
        } else if (!bars.isEmpty()) {
            appendCounts(sb, bars, type, distribution.rows() - distribution.nulls(),
                    distribution.distinct() != null && distribution.distinct() > bars.size());
        }
        sb.append("</div>\n");
        return sb.toString();
    }

    // ---------------------------------------------------------------- statistics table

    /// What a reader of the data asks first - how complete is each column, what range does it span,
    /// where does it sit on average - answered from the file's recorded statistics alone.
    private static void appendProfiles(StringBuilder sb, List<ColumnProfile> profiles, List<String> analyses) {
        // Only files that record a sum have a mean (Rust's zone map and array stats carry none),
        // so the column is left out rather than shown as a row of dashes.
        boolean withMean = profiles.stream().anyMatch(profile -> profile.mean() != null);
        boolean analyzed = !analyses.isEmpty();
        sb.append("<section class=\"card profiles\">\n<div class=\"panelhead\">Columns<span class=\"muted\">");
        if (analyzed) {
            sb.append("every column analyzed</span></div>\n");
        } else {
            sb.append("statistics recorded in the file, nothing decoded</span>")
                    .append("<button class=\"right\" id=\"analyze-all\">Analyze all</button></div>\n");
        }
        sb.append("<div class=\"scroll\">\n<table>\n")
                .append("<thead><tr><th>column</th><th>null %</th><th>min</th><th>max</th>")
                .append(withMean ? "<th>mean</th>" : "")
                .append("<th title=\"each zone's min-max band, first row to last\">range over rows</th>")
                .append("<th></th></tr></thead>\n<tbody>\n");
        for (int i = 0; i < profiles.size(); i++) {
            ColumnProfile profile = profiles.get(i);
            sb.append("<tr><td><i class=\"dot\" style=\"background:").append(fill(i)).append("\"></i><code class=\"name\">")
                    .append(escape(profile.name())).append("</code> <code class=\"dtype\">")
                    .append(escape(VortexInspector.formatDType(profile.type()))).append("</code></td><td class=\"num sized\">");
            if (profile.nullCount() == null) {
                sb.append("<span class=\"muted\">-</span>");
            } else {
                bar(sb, profile.nullCount(), profile.rows(), "var(--meta)");
                sb.append(percent(profile.nullCount(), profile.rows()));
            }
            sb.append("</td><td>").append(value(profile.min(), profile.type()))
                    .append("</td><td>").append(value(profile.max(), profile.type())).append("</td>");
            if (withMean) {
                sb.append("<td class=\"num\">").append(mean(profile)).append("</td>");
            }
            sb.append("<td>").append(sparkline(profile, fill(i))).append("</td>");
            if (analyzed) {
                // Same markup the live page's script leaves after an analysis, so its toggle works here too.
                sb.append("<td><button data-column=\"").append(i).append("\" data-state=\"done\">Hide</button></td></tr>\n")
                        .append("<tr class=\"detail\"><td colspan=\"").append(withMean ? 7 : 6).append("\">")
                        .append(analyses.get(i)).append("</td></tr>\n");
            } else {
                sb.append("<td><button data-column=\"").append(i).append("\">Analyze</button></td></tr>\n");
            }
        }
        sb.append("</tbody>\n</table>\n</div>\n</section>\n");
    }

    /// The mean, only where it reads as a number: a bool column's is its share of `true`; an
    /// extension's (a date, a timestamp) is an epoch average nobody asked for, so it is left out.
    private static String mean(ColumnProfile profile) {
        Double mean = profile.mean();
        if (mean == null) {
            return "<span class=\"muted\">-</span>";
        }
        return switch (profile.type()) {
            case DType.Bool _ -> String.format(Locale.ROOT, "%.1f%% true", 100 * mean);
            case DType.Primitive _ -> "<code>" + number(mean) + "</code>";
            default -> "<span class=\"muted\">-</span>";
        };
    }

    /// Upper bound on points in a sparkline; consecutive zones merge (min of mins, max of maxes).
    private static final int MAX_SPARK_POINTS = 120;

    /// Each zone's min-max band across the rows, as an inline SVG. Shows at a glance whether a
    /// column is sorted (a rising band), clustered (narrow bands) or random (every band spans the
    /// whole range) - which is also how well a filter on it prunes zones.
    private static String sparkline(ColumnProfile profile, String fill) {
        List<double[]> points = new ArrayList<>();
        int stride = Math.max(1, (profile.zones().size() + MAX_SPARK_POINTS - 1) / MAX_SPARK_POINTS);
        for (int i = 0; i < profile.zones().size(); i += stride) {
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (int j = i; j < Math.min(i + stride, profile.zones().size()); j++) {
                ArrayStats stats = profile.zones().get(j).stats();
                if (stats.min() instanceof Number min && stats.max() instanceof Number max) {
                    lo = Math.min(lo, min.doubleValue());
                    hi = Math.max(hi, max.doubleValue());
                }
            }
            if (lo <= hi) {
                points.add(new double[] {lo, hi});
            }
        }
        if (points.size() < 2) {
            return "<span class=\"muted\">-</span>";
        }
        double lo = points.stream().mapToDouble(p -> p[0]).min().orElse(0);
        double hi = points.stream().mapToDouble(p -> p[1]).max().orElse(0);
        double span = hi > lo ? hi - lo : 1;
        StringBuilder top = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            double x = 100.0 * i / (points.size() - 1);
            top.append(String.format(Locale.ROOT, "%.2f,%.2f ", x, 22 - 20 * (points.get(i)[1] - lo) / span));
            bottom.insert(0, String.format(Locale.ROOT, "%.2f,%.2f ", x, 22 - 20 * (points.get(i)[0] - lo) / span));
        }
        return "<svg class=\"spark\" viewBox=\"0 0 100 24\" preserveAspectRatio=\"none\" role=\"img\">"
                + "<title>zone min-max over " + count(profile.zones().size()) + " zones</title>"
                + "<polygon points=\"" + top + bottom.toString().trim() + "\" style=\"fill:" + fill
                + ";stroke:" + fill + "\"/></svg>";
    }

    // ---------------------------------------------------------------- distribution

    /// A column chart over the bins, its range labeled underneath; hovering a bar names its bin.
    private static void appendHistogram(StringBuilder sb, List<ColumnDistribution.Bar> bars, DType type) {
        long tallest = bars.stream().mapToLong(ColumnDistribution.Bar::count).max().orElse(1L);
        double width = 300.0 / bars.size();
        sb.append("<svg class=\"hist\" viewBox=\"0 0 300 80\" preserveAspectRatio=\"none\" role=\"img\">")
                .append("<title>histogram of ").append(bars.size()).append(" equal-width bins</title>");
        for (int i = 0; i < bars.size(); i++) {
            ColumnDistribution.Bar bin = bars.get(i);
            double height = tallest == 0 ? 0 : 78.0 * bin.count() / tallest;
            sb.append(String.format(Locale.ROOT, "<rect x=\"%.2f\" y=\"%.2f\" width=\"%.2f\" height=\"%.2f\">",
                            i * width + 0.5, 80 - height, width - 1, height))
                    .append("<title>").append(escape(bound(bin.value(), type))).append(" to ")
                    .append(escape(bound(bin.upper(), type))).append(": ").append(count(bin.count()))
                    .append(" rows</title></rect>");
        }
        sb.append("</svg>\n<div class=\"axis\"><code>").append(escape(bound(bars.getFirst().value(), type)))
                .append("</code><code>").append(escape(bound(bars.getLast().upper(), type))).append("</code></div>\n");
    }

    /// One bar per value, longest first or in value order, each with its count and share.
    private static void appendCounts(StringBuilder sb, List<ColumnDistribution.Bar> bars, DType type, long present,
                                     boolean top) {
        if (top) {
            sb.append("<p class=\"meta\">most frequent ").append(bars.size()).append(":</p>\n");
        }
        long widest = bars.stream().mapToLong(ColumnDistribution.Bar::count).max().orElse(1L);
        sb.append("<table class=\"bars\">\n<tbody>\n");
        for (ColumnDistribution.Bar bar : bars) {
            sb.append("<tr><td>").append(value(bar.value(), type)).append("</td><td class=\"track\">");
            bar(sb, bar.count(), widest, "var(--series-1)");
            sb.append("</td><td class=\"num\">").append(count(bar.count())).append("</td><td class=\"num muted\">")
                    .append(percent(bar.count(), present)).append("</td></tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
    }

    /// A histogram bound as the column reads: a date or timestamp bin edge shown as a date, not an
    /// epoch number.
    private static String bound(Object raw, DType type) {
        if (type instanceof DType.Extension && raw instanceof Double d) {
            return String.valueOf(readable(Math.round(d), type));
        }
        return raw instanceof Double d ? number(d) : String.valueOf(raw);
    }

    /// A whole number without decimals (a total, an integer bin edge), else two decimals, or four
    /// significant digits below one.
    private static String number(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return String.format(Locale.ROOT, "%,.0f", value);
        }
        return String.format(Locale.ROOT, Math.abs(value) >= 1 ? "%,.2f" : "%.4g", value);
    }

    /// Fetches a column's distribution into a row under it, then turns the button into a Hide/Show
    /// toggle for that row; "Analyze all" asks for every column not analyzed yet at once, and the
    /// server analyzes as many at a time as it has readers.
    private static final String SCRIPT = """
            async function analyze(button) {
              button.dataset.state = 'running';
              button.disabled = true;
              button.textContent = 'Analyzing...';
              const row = button.closest('tr');
              const response = await fetch('column/' + button.dataset.column);
              const detail = document.createElement('tr');
              detail.className = 'detail';
              const cell = document.createElement('td');
              cell.colSpan = row.children.length;
              if (response.ok) {
                cell.innerHTML = await response.text();
              } else {
                cell.textContent = 'failed: ' + response.status + ' ' + await response.text();
              }
              detail.append(cell);
              row.after(detail);
              button.dataset.state = 'done';
              button.disabled = false;
              button.textContent = 'Hide';
            }
            function toggle(button) {
              const detail = button.closest('tr').nextElementSibling;
              detail.hidden = !detail.hidden;
              button.textContent = detail.hidden ? 'Show' : 'Hide';
            }
            document.addEventListener('click', event => {
              const button = event.target.closest('button[data-column]');
              if (!button) { return; }
              if (button.dataset.state === 'done') { toggle(button); } else if (!button.dataset.state) { analyze(button); }
            });
            document.getElementById('analyze-all')?.addEventListener('click', event => {
              event.target.disabled = true;
              document.querySelectorAll('button[data-column]:not([data-state])').forEach(analyze);
            });
            """;
}
