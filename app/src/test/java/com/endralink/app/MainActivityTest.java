package com.endralink.app;

import android.view.View;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import java.io.File;
import java.io.FileOutputStream;
import org.robolectric.annotation.GraphicsMode;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Exercise the actual activity, including disabled future platforms and Back navigation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {
    @Test public void homeOpensOnlyCg50AndBackReturnsHome() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            "com.endralink.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get();
            assertEquals(View.VISIBLE, a.findViewById(R.id.homePage).getVisibility());
            assertEquals(View.GONE, a.findViewById(R.id.workspacePage).getVisibility());
            for (int id : new int[]{R.id.homeNspire, R.id.homePrime, R.id.homeDonate}) {
                Button b = a.findViewById(id);
                assertFalse(b.isEnabled());
                assertFalse(b.hasOnClickListeners());
            }
            capture(a, "home");
            a.findViewById(R.id.homeFx).performClick();
            assertEquals(View.VISIBLE, a.findViewById(R.id.workspacePage).getVisibility());
            assertEquals(View.GONE, a.findViewById(R.id.homePage).getVisibility());
            assertTrue(a.findViewById(R.id.connect).isEnabled());
            assertFalse(a.findViewById(R.id.copy).isEnabled());
            capture(a, "workspace");
            a.getOnBackPressedDispatcher().onBackPressed();
            assertEquals(View.VISIBLE, a.findViewById(R.id.homePage).getVisibility());
        }
    }
    /** Render the actual activity views for visual inspection of this build. */
    private void capture(MainActivity activity, String name) throws Exception {
        View root = activity.findViewById(R.id.page);
        int width = 720, height = 1500;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File directory = new File("build/reports/screenshots");
        directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
