package com.eldermoraes.seo.ssr;

import com.eldermoraes.seo.FaqFile;
import io.quarkus.qute.TemplateData;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * The data of ssr/faq.html (2.15.0), built from seo/faq.json with the counts
 * of the data filled in. Nothing from the host: the links are paths on this
 * site or https URLs, so the page is drawn once and reused.
 */
@TemplateData
public record FaqView(String checkedOn, String checkedOnText, List<QuestionView> questions, List<String> columns,
                      List<RowView> rows) {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    @TemplateData
    public record QuestionView(String id, String question, List<String> paragraphs, List<LinkView> links) {
    }

    /** {@code external}: an https URL, opened in a new tab like the external links of /about. */
    @TemplateData
    public record LinkView(String text, String href, boolean external) {
    }

    /** A row of the table, the source of each of its cells for the notes below it, and the days they were checked. */
    @TemplateData
    public record RowView(String label, List<String> values, List<SourceView> sources, String checkedOn) {
    }

    @TemplateData
    public record SourceView(String column, String url, String checkedOn) {
    }

    /** @param count the number of records of a type (KnownEntities) */
    static FaqView of(FaqFile file, ToIntFunction<String> count) {
        List<QuestionView> questions = new ArrayList<>();
        for (FaqFile.Question q : file.questions()) {
            questions.add(new QuestionView(q.id(), FaqFile.fill(q.question(), count),
                    q.answer().stream().map(p -> FaqFile.fill(p, count)).toList(),
                    q.links().stream().map(l -> new LinkView(l.text(), l.path(), l.path().startsWith("https://"))).toList()));
        }
        List<String> columns = file.comparison().columns();
        List<RowView> rows = new ArrayList<>();
        for (FaqFile.Row row : file.comparison().rows()) {
            List<SourceView> sources = new ArrayList<>();
            for (int i = 0; i < row.cells().size(); i++) {
                FaqFile.Cell cell = row.cells().get(i);
                sources.add(new SourceView(columns.get(i), cell.source(), cell.checkedOn().toString()));
            }
            rows.add(new RowView(row.label(), row.cells().stream().map(c -> FaqFile.fill(c.value(), count)).toList(),
                    List.copyOf(sources), String.join(", ", sources.stream().map(SourceView::checkedOn).distinct().toList())));
        }
        LocalDate checked = file.checkedOn();
        return new FaqView(checked.toString(), DAY.format(checked), List.copyOf(questions), columns, List.copyOf(rows));
    }
}
