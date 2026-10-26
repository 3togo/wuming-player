package android.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.widget.EditText;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import org.junit.Test;

public class ViewLinkageTest {

    @Test
    public void exposesDrawableConstantStateToPrecompiledProviderSignatures() throws Exception {
        assertTrue(Class.forName("android.graphics.drawable.Drawable$ConstantState") == Drawable.ConstantState.class);
        Drawable drawable = new GradientDrawable();
        org.junit.Assert.assertNull(drawable.getConstantState());
        Drawable.ConstantState state = new Drawable.ConstantState() {
            @Override public Drawable newDrawable() { return drawable; }
            @Override public int getChangingConfigurations() { return 0; }
        };
        assertTrue(state.newDrawable(new android.content.res.Resources()) == drawable);
        assertEquals(0, state.getChangingConfigurations());
    }

    @Test
    public void suppliesOnlyTheStructuralTypesNeededForNonUiProviderLinkage() {
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(320, 180);

        assertTrue(new ViewGroup() instanceof View);
        assertTrue(new ViewGroup() instanceof ViewParent);
        assertTrue(new TextView() instanceof View);
        assertTrue(new EditText() instanceof TextView);
        assertTrue(new FrameLayout() instanceof ViewGroup);
        assertTrue(new LinearLayout.LayoutParams(1, 2) instanceof ViewGroup.MarginLayoutParams);
        assertTrue(ImageView.ScaleType.CENTER_CROP.name().equals("CENTER_CROP"));
        assertEquals(320, params.width);
        assertEquals(180, params.height);
        assertEquals(0, View.class.getDeclaredMethods().length);
        assertEquals(5, View.class.getDeclaredClasses().length);
        assertTrue(java.lang.reflect.Modifier.isAbstract(Drawable.class.getModifiers()));
        assertTrue(new GradientDrawable() instanceof Drawable);
        assertTrue(new InsetDrawable() instanceof Drawable);
        assertTrue(new StateListDrawable() instanceof Drawable);
    }
}
