package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The task-scanner atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made — markers are counted per line, a word inside another
 * word is not a marker, the budget is absolute, and the marker list can be chosen — written as JUnit.
 */
class TaskScannerIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("markers are counted per line and a longer word is not a marker")
    void countsMarkersPerLineAndRespectsWordBoundaries() throws Exception {
        AtomResult run = atom.file("src/A.java", """
                        public class A {
                            // TODO: rename this
                            // FIXME later
                            // TODOSTR is not a marker
                            String s = "XXX";
                        }
                        """)
                .input("{\"maxMarkers\": \"-1\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("3", run.field("markers"), "TODO + FIXME + XXX, and TODOSTR is not one");
        assertEquals("1", run.field("todo"));
        assertEquals("1", run.field("fixme"));
        assertTrue(run.file("out/tasks/index.html").contains("rename this"), "the marker text is missing");
    }

    @Test
    @DisplayName("a budget of one trips on two markers")
    void failsOverTheBudget() throws Exception {
        AtomResult run = atom.file("x.java", "// TODO one\n// TODO two\n")
                .input("{\"maxMarkers\": \"1\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("the budget is 1"), "message: " + run.file("output.json"));
    }

    @Test
    @DisplayName("the marker list can be chosen, and the others stop counting")
    void honoursCustomMarkers() throws Exception {
        AtomResult run = atom.file("x.py", "# DEPRECATED: old api\n# TODO fine\n")
                .input("{\"markers\": \"DEPRECATED\"}")
                .run();

        run.assertOk();
        assertEquals("1", run.field("markers"), "only the chosen marker counts");
    }
}
