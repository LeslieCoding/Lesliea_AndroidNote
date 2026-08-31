package com.leslie.colorosnote.core.ink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.MotionEvent;
import android.view.View;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.junit.Test;

/**
 * Pins {@link AndroidXMotionPredictor}'s reflective target against the real
 * androidx artifact.
 *
 * <p>The wrapper swallows every failure and falls back to a no-op predictor by
 * design (prediction must never break the input pipeline). That makes a wrong
 * class or method name invisible at runtime: the app just loses low-latency
 * prediction and logs one line. Shipped builds carried
 * {@code androidx.input.prediction.MotionEventPredictor} — a package that does
 * not exist — so prediction never ran. These assertions fail loudly instead.
 */
public class MotionPredictorClassNameTest {

    @Test
    public void reflectedClassExistsOnTheRuntimeClasspath() throws Exception {
        Class<?> cls = Class.forName(AndroidXMotionPredictor.CLASS_NAME);
        assertNotNull(cls);
    }

    @Test
    public void reflectedMethodsMatchTheArtifactApi() throws Exception {
        Class<?> cls = Class.forName(AndroidXMotionPredictor.CLASS_NAME);

        Method newInstance = cls.getMethod("newInstance", View.class);
        assertTrue("newInstance must be static", Modifier.isStatic(newInstance.getModifiers()));
        assertEquals(cls, newInstance.getReturnType());

        Method record = cls.getMethod("record", MotionEvent.class);
        assertEquals(void.class, record.getReturnType());

        Method predict = cls.getMethod("predict");
        assertEquals(MotionEvent.class, predict.getReturnType());
    }
}
