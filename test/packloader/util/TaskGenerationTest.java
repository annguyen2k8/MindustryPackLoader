package packloader.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TaskGenerationTest{

    @Test
    public void testBeginInvalidatesOlderTokens(){
        TaskGeneration generation = new TaskGeneration();

        int first = generation.begin();
        assertTrue(generation.isCurrent(first));

        int second = generation.begin();
        assertFalse(generation.isCurrent(first));
        assertTrue(generation.isCurrent(second));
    }

    @Test
    public void testCancelInvalidatesCurrentToken(){
        TaskGeneration generation = new TaskGeneration();

        int token = generation.begin();
        generation.cancel();

        assertFalse(generation.isCurrent(token));
        assertEquals(2, generation.current());
    }

    @Test
    public void testCurrentReflectsCancellationAndNewTasks(){
        TaskGeneration generation = new TaskGeneration();
        assertEquals(0, generation.current());

        generation.begin();
        generation.begin();
        generation.cancel();
        assertEquals(3, generation.current());

        int newest = generation.begin();
        assertEquals(4, newest);
        assertTrue(generation.isCurrent(newest));
    }
}
