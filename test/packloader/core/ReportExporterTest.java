package packloader.core;

import arc.files.Fi;
import arc.struct.Seq;
import arc.util.serialization.Jval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import packloader.model.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class ReportExporterTest{
    private File tempDir;

    @BeforeEach
    public void setup() throws IOException{
        tempDir = Files.createTempDirectory("packloader-report-test").toFile();
    }

    @AfterEach
    public void teardown(){
        if(tempDir != null && tempDir.exists()){
            for(File f : tempDir.listFiles()){
                f.delete();
            }
            tempDir.delete();
        }
    }

    @Test
    public void testCreateFromImportReportAllStatuses(){
        ImportReport report = new ImportReport();
        report.elapsedMillis = 450;
        report.cancelled = false;

        // 1. Success new
        PackEntry e1 = new PackEntry(PackType.MAP, "C:/Secret/Path/alpha.msav", "alpha.msav", 1024);
        e1.sha256 = "hash1";
        report.addResult(ImportItemResult.success(e1, new Fi(new File(tempDir, "alpha.msav"))));

        // 2. Success renamed
        PackEntry e2 = new PackEntry(PackType.SCHEMATIC, "D:/Personal/beta.msch", "beta.msch", 2048);
        e2.sha256 = "hash2";
        report.addResult(ImportItemResult.success(e2, new Fi(new File(tempDir, "beta_1.msch"))));

        // 3. Overwritten
        PackEntry e3 = new PackEntry(PackType.MAP, "gamma.msav", "gamma.msav", 3072);
        e3.sha256 = "hash3";
        report.addResult(ImportItemResult.overwritten(e3, new Fi(new File(tempDir, "gamma.msav"))));

        // 4. Skipped
        PackEntry e4 = new PackEntry(PackType.MAP, "delta.msav", "delta.msav", 4096);
        report.addResult(ImportItemResult.skipped(e4, "Identical content to existing"));

        // 5. Failed
        PackEntry e5 = new PackEntry(PackType.SCHEMATIC, "epsilon.msch", "epsilon.msch", 512);
        report.addResult(ImportItemResult.failed(e5, "Corrupted header stream"));

        ReportModel model = ReportExporter.createFromImportReport(report, "my_source_pack.zip");

        assertEquals("import", model.reportType);
        assertEquals("my_source_pack.zip", model.sourceName);
        assertEquals(450, model.elapsedMillis);
        assertFalse(model.cancelled);

        assertEquals(5, model.summary.total);
        assertEquals(2, model.summary.success);
        assertEquals(1, model.summary.overwritten);
        assertEquals(1, model.summary.skipped);
        assertEquals(1, model.summary.failed);

        assertEquals(5, model.items.size);

        // Verify items
        ReportModel.ReportItem i1 = model.items.get(0);
        assertEquals("alpha.msav", i1.fileName);
        assertEquals("MAP", i1.type);
        assertEquals("SUCCESS", i1.status);
        assertEquals("alpha.msav", i1.destination);
        assertEquals(1024, i1.sizeBytes);
        assertEquals("hash1", i1.sha256);
        assertEquals("", i1.error);

        ReportModel.ReportItem i2 = model.items.get(1);
        assertEquals("beta.msch", i2.fileName);
        assertEquals("SCHEMATIC", i2.type);
        assertEquals("SUCCESS", i2.status);
        assertEquals("beta_1.msch", i2.destination);

        ReportModel.ReportItem i3 = model.items.get(2);
        assertEquals("OVERWRITTEN", i3.status);

        ReportModel.ReportItem i4 = model.items.get(3);
        assertEquals("SKIPPED", i4.status);
        assertEquals("Identical content to existing", i4.result);
        assertEquals("", i4.destination);

        ReportModel.ReportItem i5 = model.items.get(4);
        assertEquals("FAILED", i5.status);
        assertEquals("Corrupted header stream", i5.error);
        assertEquals("", i5.destination);
    }

    @Test
    public void testCreateFromDryRunPlan(){
        DryRunPlan plan = new DryRunPlan();

        PackEntry e1 = new PackEntry(PackType.MAP, "m1.msav", "m1.msav", 100);
        plan.items.add(new DryRunPlan.DryRunItem(e1, DryRunAction.NEW, "m1.msav"));
        plan.newCount++;

        PackEntry e2 = new PackEntry(PackType.MAP, "m2.msav", "m2.msav", 200);
        plan.items.add(new DryRunPlan.DryRunItem(e2, DryRunAction.OVERWRITE, "m2.msav"));
        plan.overwriteCount++;

        PackEntry e3 = new PackEntry(PackType.SCHEMATIC, "s1.msch", "s1.msch", 300);
        plan.items.add(new DryRunPlan.DryRunItem(e3, DryRunAction.RENAME, "s1_1.msch"));
        plan.renameCount++;

        PackEntry e4 = new PackEntry(PackType.SCHEMATIC, "s2.msch", "s2.msch", 400);
        plan.items.add(new DryRunPlan.DryRunItem(e4, DryRunAction.SKIP, "s2.msch", true, "orig.msch"));
        plan.skipCount++;

        ReportModel model = ReportExporter.createFromDryRunPlan(plan, "DryRunTest");

        assertEquals("plan", model.reportType);
        assertEquals("DryRunTest", model.sourceName);
        assertEquals(4, model.summary.total);
        assertEquals(2, model.summary.success); // NEW + RENAME
        assertEquals(1, model.summary.overwritten);
        assertEquals(1, model.summary.skipped);
        assertEquals(0, model.summary.failed);

        assertEquals(4, model.items.size);
        assertEquals("NEW", model.items.get(0).status);
        assertEquals("OVERWRITE", model.items.get(1).status);
        assertEquals("RENAME", model.items.get(2).status);
        assertEquals("SKIP", model.items.get(3).status);
        assertTrue(model.items.get(3).result.contains("orig.msch"));
    }

    @Test
    public void testJsonSerializationAndStructure(){
        ReportModel model = new ReportModel();
        model.reportType = "import";
        model.sourceName = "test-pack.zip";
        model.elapsedMillis = 120;
        model.summary.total = 1;
        model.summary.success = 1;

        ReportModel.ReportItem item = new ReportModel.ReportItem();
        item.fileName = "test.msav";
        item.type = "MAP";
        item.status = "SUCCESS";
        item.result = "Imported";
        item.destination = "test.msav";
        item.sizeBytes = 500;
        item.sha256 = "abc123hash";
        item.source = "test.msav";
        model.items.add(item);

        String json = ReportExporter.generateJson(model);
        assertNotNull(json);
        assertTrue(json.startsWith("{"));
        assertTrue(json.endsWith("}"));

        // Parse with Jval
        Jval parsed = Jval.read(json);
        assertEquals("import", parsed.getString("reportType"));
        assertEquals("test-pack.zip", parsed.getString("sourceName"));
        assertEquals(120, parsed.getLong("elapsedMillis", 0));
        assertFalse(parsed.getBool("cancelled", false));

        Jval summary = parsed.get("summary");
        assertNotNull(summary);
        assertEquals(1, summary.getInt("total", 0));
        assertEquals(1, summary.getInt("success", 0));

        Jval items = parsed.get("items");
        assertNotNull(items);
        assertTrue(items.isArray());
        assertEquals(1, items.asArray().size);

        Jval it = items.asArray().get(0);
        assertEquals("test.msav", it.getString("fileName"));
        assertEquals("MAP", it.getString("type"));
        assertEquals("SUCCESS", it.getString("status"));
        assertEquals("Imported", it.getString("result"));
        assertEquals("test.msav", it.getString("destination"));
        assertEquals(500, it.getLong("sizeBytes", 0));
        assertEquals("abc123hash", it.getString("sha256"));
    }

    @Test
    public void testCsvSerializationRfc4180AndUtf8Bom(){
        ReportModel model = new ReportModel();

        ReportModel.ReportItem item1 = new ReportModel.ReportItem();
        item1.fileName = "simple.msav";
        item1.type = "MAP";
        item1.status = "SUCCESS";
        item1.result = "Normal import";
        item1.destination = "simple.msav";
        item1.sizeBytes = 100;
        item1.sha256 = "h1";
        item1.source = "simple.msav";
        item1.error = "";
        model.items.add(item1);

        // Item needing CSV quotes: contains comma and quotes and newline
        ReportModel.ReportItem item2 = new ReportModel.ReportItem();
        item2.fileName = "comma,name.msch";
        item2.type = "SCHEMATIC";
        item2.status = "FAILED";
        item2.result = "Failed with \"quotes\" and\nnewline, error";
        item2.destination = "";
        item2.sizeBytes = 200;
        item2.sha256 = "";
        item2.source = "comma,name.msch";
        item2.error = "Error: \"quote\"";
        model.items.add(item2);

        String csv = ReportExporter.generateCsv(model);
        assertNotNull(csv);

        // UTF-8 BOM must be the very first char
        assertEquals('\uFEFF', csv.charAt(0));

        String content = csv.substring(1);
        String[] lines = content.split("\r\n");
        assertTrue(lines.length >= 3);
        assertEquals("fileName,type,status,result,destination,sizeBytes,sha256,source,error", lines[0]);

        // Line 1: simple.msav
        assertTrue(lines[1].startsWith("simple.msav,MAP,SUCCESS,Normal import,simple.msav,100,h1,simple.msav,"));

        // CSV must properly escape quotes as "" and wrap fields with commas/newlines
        assertTrue(csv.contains("\"comma,name.msch\""));
        assertTrue(csv.contains("\"Failed with \"\"quotes\"\" and\nnewline, error\""));
        assertTrue(csv.contains("\"Error: \"\"quote\"\"\""));
    }

    @Test
    public void testEmptyReportHandling(){
        ReportModel emptyModel = new ReportModel();
        String json = ReportExporter.generateJson(emptyModel);
        assertNotNull(json);
        Jval parsed = Jval.read(json);
        assertEquals(0, parsed.get("items").asArray().size);
        assertEquals(0, parsed.get("summary").getInt("total", -1));

        String csv = ReportExporter.generateCsv(emptyModel);
        assertNotNull(csv);
        assertEquals('\uFEFF', csv.charAt(0));
        assertTrue(csv.contains("fileName,type,status,result"));

        // Null safety
        assertEquals("{}", ReportExporter.generateJson(null));
        String nullCsv = ReportExporter.generateCsv(null);
        assertTrue(nullCsv.startsWith("\uFEFF"));
    }

    @Test
    public void testNullAndMissingFieldsSafety(){
        ReportModel model = new ReportModel();
        ReportModel.ReportItem item = new ReportModel.ReportItem();
        item.fileName = null;
        item.type = null;
        item.status = null;
        item.result = null;
        item.destination = null;
        item.sha256 = null;
        item.source = null;
        item.error = null;
        model.items.add(item);

        assertDoesNotThrow(() -> {
            String json = ReportExporter.generateJson(model);
            Jval parsed = Jval.read(json);
            assertEquals("", parsed.get("items").asArray().get(0).getString("fileName"));

            String csv = ReportExporter.generateCsv(model);
            assertNotNull(csv);
        });
    }

    @Test
    public void testSafeSourceNameNoPathLeak(){
        PackEntry e1 = new PackEntry(PackType.MAP, "C:\\Users\\JohnDoe\\AppData\\Local\\Mindustry\\maps\\secret.msav", "secret.msav", 100);
        String s1 = ReportExporter.safeSourceName(e1);
        assertEquals("secret.msav", s1);
        assertFalse(s1.contains("JohnDoe"));
        assertFalse(s1.contains("Users"));

        PackEntry e2 = new PackEntry(PackType.SCHEMATIC, "/home/alice/.config/Mindustry/schematics/base.msch", "base.msch", 200);
        String s2 = ReportExporter.safeSourceName(e2);
        assertEquals("base.msch", s2);
        assertFalse(s2.contains("alice"));
        assertFalse(s2.contains("home"));

        PackEntry e3 = new PackEntry(PackType.MAP, "pack.zip", "nested.msav", 300);
        e3.zipFile = new Fi(new File(tempDir, "pack.zip"));
        e3.zipEntryName = "subfolder/nested.msav";
        String s3 = ReportExporter.safeSourceName(e3);
        assertEquals("pack.zip/subfolder/nested.msav", s3);
    }

    @Test
    public void testWriteJsonAndCsvToFi() throws IOException{
        ReportModel model = new ReportModel();
        model.sourceName = "write-test";
        ReportModel.ReportItem item = new ReportModel.ReportItem();
        item.fileName = "item.msav";
        item.type = "MAP";
        item.status = "SUCCESS";
        model.items.add(item);

        Fi jsonFi = new Fi(new File(tempDir, "subdir/out.json"));
        ReportExporter.writeJson(model, jsonFi);
        assertTrue(jsonFi.exists());
        String jsonContent = jsonFi.readString(StandardCharsets.UTF_8.name());
        assertTrue(jsonContent.contains("write-test"));
        assertTrue(jsonContent.contains("item.msav"));

        Fi csvFi = new Fi(new File(tempDir, "subdir/out.csv"));
        ReportExporter.writeCsv(model, csvFi);
        assertTrue(csvFi.exists());
        String csvContent = csvFi.readString(StandardCharsets.UTF_8.name());
        assertTrue(csvContent.startsWith("\uFEFF"));
        assertTrue(csvContent.contains("item.msav"));
    }

    @Test
    public void testDefaultReportFileNameSanitization(){
        ReportModel model = new ReportModel();
        model.reportType = "import";
        model.sourceName = "My Downloads / Best Maps: 2026!";

        String name = ReportExporter.defaultReportFileName(model, "json");
        assertTrue(name.startsWith("packloader-import-"));
        assertTrue(name.endsWith(".json"));
        assertFalse(name.contains("/"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("!"));
        assertFalse(name.contains(" "));
    }
}
