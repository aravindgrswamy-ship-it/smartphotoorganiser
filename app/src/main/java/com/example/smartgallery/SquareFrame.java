package com.example.smartgallery;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** A FrameLayout that is always as tall as it is wide, so grid cells stay square. */
public class SquareFrame extends FrameLayout {
    public SquareFrame(Context c) { super(c); }
    public SquareFrame(Context c, AttributeSet a) { super(c, a); }
    public SquareFrame(Context c, AttributeSet a, int s) { super(c, a, s); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, widthSpec);
    }
}
