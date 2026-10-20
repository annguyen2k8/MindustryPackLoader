package packloader;

import org.junit.jupiter.api.Test;
import packloader.ui.UIUtils;

import static org.junit.jupiter.api.Assertions.assertNull;

public class UIUtilsTest{

    @Test
    void testPadNullSafe(){
        assertNull(UIUtils.pad(null));
    }
}
