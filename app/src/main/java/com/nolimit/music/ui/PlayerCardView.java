package com.nolimit.music.ui;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.nolimit.music.PlayerActivity;

public final class PlayerCardView extends LinearLayout {
    public PlayerCardView(Context context) {
        this(context, null);
    }

    public PlayerCardView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PlayerCardView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> context.startActivity(new Intent(context, PlayerActivity.class)));
    }
}
