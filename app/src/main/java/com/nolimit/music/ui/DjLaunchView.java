package com.nolimit.music.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatTextView;

import com.nolimit.music.DjActivity;

public final class DjLaunchView extends AppCompatTextView {
    public DjLaunchView(Context context) {
        super(context);
        init();
    }

    public DjLaunchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DjLaunchView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOnClickListener(v -> getContext().startActivity(new Intent(getContext(), DjActivity.class)));
    }
}
