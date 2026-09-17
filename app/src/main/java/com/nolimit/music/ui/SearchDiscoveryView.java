package com.nolimit.music.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.nolimit.music.R;
import com.nolimit.music.data.YoutubeChartsRepository;
import com.nolimit.music.data.YoutubeRepository;
import com.nolimit.music.model.SearchResult;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SearchDiscoveryView extends LinearLayout implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private LinearLayout sourceRow;
    private LinearLayout popularRow;
    private LinearLayout recentRow;
    private TextView popularStatus;

    public SearchDiscoveryView(Context context) {
        this(context, null);
    }

    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setPadding(0, dp(10), 0, dp(4));
        prefs = context.getSharedPreferences(YoutubeRepository.SETTINGS_PREFS, Context.MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        TextView sourceTitle = label("검색 소스", 12, true);
        addView(sourceTitle);

        sourceRow = new LinearLayout(getContext());
        sourceRow.setOrientation(HORIZONTAL);
        LayoutParams sourceLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(42));
        sourceLp.topMargin = dp(6);
        addView(sourceRow, sourceLp);
        renderSourceButtons();

        LinearLayout popularHeader = new LinearLayout(getContext());
        popularHeader.setOrientation(HORIZONTAL);
        popularHeader.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams phLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        phLp.topMargin = dp(12);
        addView(popularHeader, phLp);

        TextView popularTitle = label("지금 많이 찾는 음악", 12, true);
        popularHeader.addView(popularTitle, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        popularStatus = label("불러오는 중", 10, false);
        popularHeader.addView(popularStatus);

        popularRow = new LinearLayout(getContext());
        popularRow.setOrientation(HORIZONTAL);
        HorizontalScrollView popularScroll = horizontal(popularRow);
        LayoutParams psLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(42));
        psLp.topMargin = dp(5);
        addView(popularScroll, psLp);

        LinearLayout recentHeader = new LinearLayout(getContext());
        recentHeader.setOrientation(HORIZONTAL);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rhLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rhLp.topMargin = dp(9);
        addView(recentHeader, rhLp);

        recentHeader.addView(label("최근 검색어", 12, true), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = label("지우기", 10, false);
        clear.setPadding(dp(10), dp(4), dp(2), dp(4));
        clear.setOnClickListener(v -> prefs.edit().remove(YoutubeRepository.KEY_RECENT_SEARCHES).apply());
        recentHeader.addView(clear);

        recentRow = new LinearLayout(getContext());
        recentRow.setOrientation(HORIZONTAL);
        HorizontalScrollView recentScroll = horizontal(recentRow);
        LayoutParams rsLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(42));
        rsLp.topMargin = dp(4);
        addView(recentScroll, rsLp);

        renderRecentSearches();
        loadPopularTerms();
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private void renderSourceButtons() {
        sourceRow.removeAllViews();
        String selected = prefs.getString(YoutubeRepository.KEY_SEARCH_SOURCE, "music_first");
        addSourceChip("음악 우선", "music_first", selected);
        addSourceChip("YouTube Music", "youtube_music", selected);
        addSourceChip("YouTube", "youtube", selected);
    }

    private void addSourceChip(String label, String value, String selected) {
        TextView chip = chip(label, value.equals(selected));
        chip.setOnClickListener(v -> {
            prefs.edit().putString(YoutubeRepository.KEY_SEARCH_SOURCE, value).apply();
            renderSourceButtons();
        });
        sourceRow.addView(chip);
    }

    private void loadPopularTerms() {
        io.execute(() -> {
            List<String> terms = new ArrayList<>();
            try {
                YoutubeChartsRepository charts = new YoutubeChartsRepository();
                List<SearchResult> list;
                try {
                    list = charts.loadChart(YoutubeChartsRepository.Category.TRENDING, "kr", 12);
                } catch (Exception e) {
                    list = charts.loadChart(YoutubeChartsRepository.Category.TOP_SONGS, "kr", 12);
                }
                Set<String> unique = new LinkedHashSet<>();
                for (SearchResult item : list) {
                    if (item.title != null && !item.title.trim().isEmpty()) unique.add(item.title.trim());
                    if (unique.size() >= 10) break;
                }
                terms.addAll(unique);
            } catch (Exception ignored) {
            }
            post(() -> renderPopularTerms(terms));
        });
    }

    private void renderPopularTerms(List<String> terms) {
        popularRow.removeAllViews();
        if (terms.isEmpty()) {
            popularStatus.setText("차트 연결 안 됨");
            TextView empty = label("최근 검색어를 이용해 주세요", 11, false);
            popularRow.addView(empty, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
            return;
        }
        popularStatus.setText(terms.size() + "개");
        int rank = 1;
        for (String term : terms) {
            TextView chip = chip(rank + "  " + term, false);
            chip.setOnClickListener(v -> search(term));
            popularRow.addView(chip);
            rank++;
        }
    }

    private void renderRecentSearches() {
        recentRow.removeAllViews();
        try {
            JSONArray array = new JSONArray(prefs.getString(YoutubeRepository.KEY_RECENT_SEARCHES, "[]"));
            if (array.length() == 0) {
                recentRow.addView(label("아직 검색 기록이 없습니다", 11, false));
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                String term = array.optString(i).trim();
                if (term.isEmpty()) continue;
                TextView chip = chip(term, false);
                chip.setOnClickListener(v -> search(term));
                recentRow.addView(chip);
            }
        } catch (Exception e) {
            recentRow.addView(label("아직 검색 기록이 없습니다", 11, false));
        }
    }

    private void search(String term) {
        View root = getRootView();
        EditText input = root.findViewById(R.id.etSearch);
        View button = root.findViewById(R.id.btnSearch);
        if (input == null || button == null) return;
        input.setText(term);
        input.setSelection(input.getText().length());
        button.performClick();
    }

    private TextView chip(String text, boolean selected) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(11f);
        view.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setBackgroundResource(selected ? R.drawable.bg_nav_active : R.drawable.bg_smart_card);
        view.setPadding(dp(13), 0, dp(13), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(36));
        lp.setMarginEnd(dp(7));
        view.setLayoutParams(lp);
        return view;
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(ContextCompat.getColor(getContext(), bold ? R.color.text_primary : R.color.muted));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
        renderSourceButtons();
        renderRecentSearches();
    }

    @Override protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        super.onDetachedFromWindow();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (YoutubeRepository.KEY_SEARCH_SOURCE.equals(key)) renderSourceButtons();
        if (YoutubeRepository.KEY_RECENT_SEARCHES.equals(key)) renderRecentSearches();
    }
}
