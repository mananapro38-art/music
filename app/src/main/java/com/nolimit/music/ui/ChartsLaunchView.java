package com.nolimit.music.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import com.nolimit.music.ChartsActivity;

public final class ChartsLaunchView extends AppCompatTextView {
    public ChartsLaunchView(Context context) {
        super(context);
        init();
    }

    public ChartsLaunchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ChartsLaunchView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(v -> getContext().startActivity(new Intent(getContext(), ChartsActivity.class)));
    }
}
