package de.seism0saurus.glacier.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QR-2 ratchet: keeps the GDPR German-authoring worksheet complete and in sync with the
 * English catalog so that the operator can supply authoritative German legal text.
 *
 * <p><b>Context.</b> German is Glacier's <em>source</em> locale (template default text;
 * see {@code .claude/skills/angular-i18n-localize.md}). The GDPR/legal dialog
 * ({@code gdpr.component.html}) is the one component that still ships English placeholder
 * defaults pending authoritative German legal authoring. Rather than machine-translating a
 * privacy policy, the mechanism is wired and the German wording is operator-supplied: every
 * {@code gdpr.*} string has an entry in {@code gdpr.de.worksheet.json} with an empty
 * {@code "de"} slot to fill.
 *
 * <p>This test is the ratchet that prevents the wiring from silently regressing. It does NOT
 * fail on empty {@code "de"} slots (that is the operator's pending work) — it fails when the
 * worksheet drifts from reality:
 * <ol>
 *   <li>The worksheet's key set must exactly equal the {@code gdpr.*} {@code @@id}s referenced
 *       in {@code gdpr.component.html} — so a NEW English-only GDPR string cannot be added
 *       without also registering it for German authoring, and a removed string cannot leave a
 *       stale worksheet entry.</li>
 *   <li>Each worksheet entry's {@code "en"} value must equal the corresponding
 *       {@code messages.en.json} value — so the operator always translates the CURRENT English
 *       source, never a stale copy.</li>
 *   <li>Each entry must carry a {@code "de"} field (string) — the slot to fill.</li>
 * </ol>
 *
 * <p><b>Mode applicability</b>: mode-agnostic — legal-page content is independent of Glacier's
 * operational mode (live / fallback / killswitch / insecure).
 */
class GdprGermanAuthoringWorksheetTest {

    private static final Path TEMPLATE =
            Paths.get("frontend/src/app/gdpr/gdpr.component.html");
    private static final Path EN_CATALOG =
            Paths.get("frontend/src/assets/i18n/messages.en.json");
    private static final Path WORKSHEET =
            Paths.get("frontend/src/assets/i18n/gdpr.de.worksheet.json");

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    // @@gdpr.<id> — word chars, dots, hyphens (matches the convention in check-i18n-parity.mjs)
    private static final Pattern GDPR_ID = Pattern.compile("@@(gdpr\\.[\\w.-]+)");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** gdpr.* @@ids actually referenced by the template (HTML comments stripped first). */
    private static Set<String> templateGdprIds() throws IOException {
        String html = Files.readString(TEMPLATE);
        String withoutComments = HTML_COMMENT.matcher(html).replaceAll("");
        Set<String> ids = new TreeSet<>();
        Matcher m = GDPR_ID.matcher(withoutComments);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    private static JsonNode readJson(Path p) throws IOException {
        try (var in = Files.newInputStream(p)) {
            return MAPPER.readTree(in);
        }
    }

    @Test
    void worksheetKeysMatchTemplateGdprIdsExactly() throws IOException {
        Set<String> templateIds = templateGdprIds();
        assertThat(templateIds)
                .as("sanity: gdpr.component.html must reference at least one gdpr.* @@id")
                .isNotEmpty();

        JsonNode worksheet = readJson(WORKSHEET);
        Set<String> worksheetKeys = new LinkedHashSet<>();
        worksheet.fieldNames().forEachRemaining(k -> {
            if (!"@@meta".equals(k)) {
                worksheetKeys.add(k);
            }
        });

        assertThat(worksheetKeys)
                .as("gdpr.de.worksheet.json keys must exactly match the gdpr.* @@ids in the template — "
                        + "a new English-only GDPR string must be registered for German authoring; "
                        + "a removed string must not leave a stale worksheet entry")
                .containsExactlyInAnyOrderElementsOf(templateIds);
    }

    @Test
    void worksheetEnglishStaysInSyncWithCatalog() throws IOException {
        JsonNode worksheet = readJson(WORKSHEET);
        JsonNode catalog = readJson(EN_CATALOG);

        worksheet.fieldNames().forEachRemaining(key -> {
            if ("@@meta".equals(key)) return;
            JsonNode entry = worksheet.get(key);

            assertThat(entry.has("en") && entry.get("en").isTextual())
                    .as("worksheet entry %s must have a textual 'en' source value", key)
                    .isTrue();
            assertThat(entry.has("de") && entry.get("de").isTextual())
                    .as("worksheet entry %s must have a 'de' slot (string, possibly empty) to fill", key)
                    .isTrue();

            JsonNode catalogValue = catalog.get(key);
            assertThat(catalogValue)
                    .as("worksheet key %s must exist in messages.en.json", key)
                    .isNotNull();
            assertThat(entry.get("en").asText())
                    .as("worksheet 'en' for %s must equal messages.en.json (operator must translate the CURRENT source; "
                            + "regenerate the worksheet if the English changed)", key)
                    .isEqualTo(catalogValue.asText());
        });
    }
}
