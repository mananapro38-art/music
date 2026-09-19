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

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
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
    private LinearLayout optionsPanel;
    private LinearLayout popularPanel;
    private ChipGroup sourceGroup;
    private ChipGroup filterGroup;
    private LinearLayout popularRow;
    private LinearLayout recentRow;
    private TextView sourceSummary;
    private TextView optionsArrow;
    private TextView popularArrow;
    private TextView popularStatus;
    private boolean popularLoaded;

    public SearchDiscoveryView(Context context) { this(context, null); }
    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setPadding(0, dp(6), 0, dp(2));
        prefs = context.getSharedPreferences(YoutubeRepository.SETTINGS_PREFS, Context.MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout optionsHeader = glassRow();
        TextView optionsTitle = label("검색 옵션", 13, true);
        sourceSummary = label(sourceLabel(), 11, false);
        optionsArrow = label("⌄", 18, false);
        optionsHeader.addView(optionsTitle, new LayoutParams(0, dp(40), 1f));
        optionsHeader.addView(sourceSummary);
        optionsHeader.addView(optionsArrow, new LayoutParams(dp(30), dp(40)));
        optionsHeader.setOnClickListener(v -> toggleOptions());
        addView(optionsHeader);

        optionsPanel = new LinearLayout(getContext());
        optionsPanel.setOrientation(VERTICAL);
        optionsPanel.setVisibility(GONE);
        LayoutParams optionLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        optionLp.topMargin = dp(5);
        addView(optionsPanel, optionLp);

        optionsPanel.addView(label("검색 플랫폼", 11, true));
        sourceGroup = new ChipGroup(getContext());
        sourceGroup.setSingleSelection(true);
        sourceGroup.setSelectionRequired(true);
        sourceGroup.setSingleLine(true);
        sourceGroup.setChipSpacingHorizontal(dp(4));
        HorizontalScrollView sourceScroll = new HorizontalScrollView(getContext());
        sourceScroll.setHorizontalScrollBarEnabled(false);
        sourceScroll.addView(sourceGroup, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        optionsPanel.addView(sourceScroll);
        renderSourceButtons();

        TextView filterTitle = label("결과 필터", 11, true);
        LayoutParams ft = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        ft.topMargin = dp(5);
        optionsPanel.addView(filterTitle, ft);
        filterGroup = new ChipGroup(getContext());
        filterGroup.setSingleLine(false);
        filterGroup.setChipSpacingHorizontal(dp(4));
        filterGroup.setChipSpacingVertical(dp(2));
        optionsPanel.addView(filterGroup);
        renderFilterButtons();

        LinearLayout recentHeader = new LinearLayout(getContext());
        recentHeader.setOrientation(HORIZONTAL);
        recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rhLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rhLp.topMargin = dp(7);
        addView(recentHeader, rhLp);
        recentHeader.addView(label("최근 검색", 12, true), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = label("지우기", 10, false);
        clear.setPadding(dp(8), dp(3), 0, dp(3));
        clear.setOnClickListener(v -> prefs.edit().remove(YoutubeRepository.KEY_RECENT_SEARCHES).apply());
        recentHeader.addView(clear);

        recentRow = new LinearLayout(getContext());
        recentRow.setOrientation(HORIZONTAL);
        HorizontalScrollView recentScroll = horizontal(recentRow);
        LayoutParams rsLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(38));
        rsLp.topMargin = dp(2);
        addView(recentScroll, rsLp);

        LinearLayout popularHeader = glassRow();
        LayoutParams phLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(40));
        phLp.topMargin = dp(5);
        addView(popularHeader, phLp);
        popularHeader.addView(label("추천 키워드", 12, true), new LinearLayout.LayoutParams(0, dp(40), 1f));
        popularStatus = label("", 10, false);
        popularHeader.addView(popularStatus);
        popularArrow = label("›", 20, false);
        popularHeader.addView(popularArrow, new LinearLayout.LayoutParams(dp(30), dp(40)));
        popularHeader.setOnClickListener(v -> togglePopular());

        popularPanel = new LinearLayout(getContext());
        popularPanel.setOrientation(VERTICAL);
        popularPanel.setVisibility(GONE);
        popularRow = new LinearLayout(getContext());
        popularRow.setOrientation(HORIZONTAL);
        HorizontalScrollView popularScroll = horizontal(popularRow);
        LayoutParams psLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(38));
        psLp.topMargin = dp(3);
        popularPanel.addView(popularScroll, psLp);
        addView(popularPanel);

        renderRecentSearches();
    }

    private LinearLayout glassRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(4), 0);
        row.setBackgroundResource(R.drawable.bg_search);
        return row;
    }

    private void toggleOptions() {
        boolean open = optionsPanel.getVisibility() != VISIBLE;
        optionsPanel.setVisibility(open ? VISIBLE : GONE);
        optionsArrow.setText(open ? "⌃" : "⌄");
    }

    private void togglePopular() {
        boolean open = popularPanel.getVisibility() != VISIBLE;
        popularPanel.setVisibility(open ? VISIBLE : GONE);
        popularArrow.setText(open ? "⌄" : "›");
        if (open && !popularLoaded) {
            popularLoaded = true;
            popularStatus.setText("불러오는 중");
            loadPopularTerms();
        }
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext());
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return scroll;
    }

    private String sourceLabel() {
        String value = prefs.getString(YoutubeRepository.KEY_SEARCH_SOURCE, "music_first");
        if ("all".equals(value)) return "통합";
        if ("youtube_music".equals(value)) return "YouTube Music";
        if ("youtube".equals(value)) return "YouTube";
        if ("soundcloud".equals(value)) return "SoundCloud";
        if ("audius".equals(value)) return "Audius";
        if ("bandcamp".equals(value)) return "Bandcamp";
        return "음악 우선";
    }

    private void renderSourceButtons() {
        if (sourceGroup == null) return;
        sourceGroup.removeAllViews();
        String selected = prefs.getString(YoutubeRepository.KEY_SEARCH_SOURCE, "music_first");
        addSourceChip("음악 우선", "music_first", selected);
        addSourceChip("통합", "all", selected);
        addSourceChip("YouTube Music", "youtube_music", selected);
        addSourceChip("YouTube", "youtube", selected);
        addSourceChip("SoundCloud", "soundcloud", selected);
        addSourceChip("Audius", "audius", selected);
        addSourceChip("Bandcamp", "bandcamp", selected);
        if (sourceSummary != null) sourceSummary.setText(sourceLabel());
    }

    private void addSourceChip(String label, String value, String selected) {
        Chip chip = baseChip(label);
        chip.setCheckable(true);
        chip.setChecked(value.equals(selected));
        chip.setOnClickListener(v -> prefs.edit().putString(YoutubeRepository.KEY_SEARCH_SOURCE, value).apply());
        sourceGroup.addView(chip);
    }

    private void renderFilterButtons() {
        if (filterGroup == null) return;
        filterGroup.removeAllViews();
        addToggleChip("공식 음원", YoutubeRepository.KEY_FILTER_OFFICIAL, false);
        addToggleChip("라이브 제외", YoutubeRepository.KEY_FILTER_EXCLUDE_LIVE, true);
        addToggleChip("커버 제외", YoutubeRepository.KEY_FILTER_EXCLUDE_COVER, true);
        addToggleChip("리믹스 포함", YoutubeRepository.KEY_FILTER_INCLUDE_REMIX, false);
    }

    private void addToggleChip(String title, String key, boolean defaultValue) {
        Chip chip = baseChip(title);
        chip.setCheckable(true);
        chip.setChecked(prefs.getBoolean(key, defaultValue));
        chip.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply());
        filterGroup.addView(chip);
    }

    private Chip baseChip(String title) {
        Chip chip = new Chip(getContext());
        chip.setText(title);
        chip.setTextSize(11f);
        chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(30));
        chip.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        return chip;
    }

    private void loadPopularTerms() {
        io.execute(() -> {
            List<String> terms = new ArrayList<>();
            try {
                YoutubeChartsRepository charts = new YoutubeChartsRepository();
                List<SearchResult> list;
                try { list = charts.loadChart(YoutubeChartsRepository.Category.TRENDING, "kr", 12); }
                catch (Exception e) { list = charts.loadChart(YoutubeChartsRepository.Category.TOP_SONGS, "kr", 12); }
                Set<String> unique = new LinkedHashSet<>();
                for (SearchResult item : list) {
                    if (item.title != null && !item.title.trim().isEmpty()) unique.add(item.title.trim());
                    if (unique.size() >= 10) break;
                }
                terms.addAll(unique);
            } catch (Exception ignored) { }
            post(() -> renderPopularTerms(terms));
        });
    }

    private void renderPopularTerms(List<String> terms) {
        popularRow.removeAllViews();
        if (terms.isEmpty()) {
            popularStatus.setText("연결 안 됨");
            popularRow.addView(label("최근 검색을 이용해 주세요", 11, false));
            return;
        }
        popularStatus.setText(terms.size() + "개");
        int rank = 1;
        for (String term : terms) {
            Chip chip = baseChip(rank + "  " + term);
            chip.setOnClickListener(v -> search(term));
            popularRow.addView(chip);
            rank++;
        }
    }

    private void renderRecentSearches() {
        if (recentRow == null) return;
        recentRow.removeAllViews();
        try {
            JSONArray array = new JSONArray(prefs.getString(YoutubeRepository.KEY_RECENT_SEARCHES, "[]"));
            if (array.length() == 0) {
                recentRow.addView(label("검색 기록이 여기에 표시됩니다", 11, false));
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                String term = array.optString(i).trim();
                if (term.isEmpty()) continue;
                Chip chip = baseChip(term);
                chip.setOnClickListener(v -> search(term));
                recentRow.addView(chip);
            }
        } catch (Exception e) {
            recentRow.addView(label("검색 기록이 여기에 표시됩니다", 11, false));
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

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setTextColor(ContextCompat.getColor(getContext(), bold ? R.color.text_primary : R.color.muted));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        prefs.registerOnSharedPreferenceChangeListener(this);
        renderSourceButtons();
        renderFilterButtons();
        renderRecentSearches();
    }

    @Override protected void onDetachedFromWindow() {
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        io.shutdownNow();
        super.onDetachedFromWindow();
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (YoutubeRepository.KEY_SEARCH_SOURCE.equals(key)) renderSourceButtons();
        if (YoutubeRepository.KEY_RECENT_SEARCHES.equals(key)) renderRecentSearches();
    }
}
