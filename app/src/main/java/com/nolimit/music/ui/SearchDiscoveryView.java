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
    private ChipGroup sourceGroup;
    private ChipGroup filterGroup;
    private LinearLayout popularRow;
    private LinearLayout recentRow;
    private TextView popularStatus;

    public SearchDiscoveryView(Context context) { this(context, null); }
    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public SearchDiscoveryView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setPadding(0, dp(12), 0, dp(4));
        prefs = context.getSharedPreferences(YoutubeRepository.SETTINGS_PREFS, Context.MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        LinearLayout sourceHeader = new LinearLayout(getContext());
        sourceHeader.setOrientation(HORIZONTAL); sourceHeader.setGravity(Gravity.CENTER_VERTICAL);
        sourceHeader.addView(label("검색 플랫폼", 12, true), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        sourceHeader.addView(label("통합 검색 지원", 10, false));
        addView(sourceHeader);

        sourceGroup = new ChipGroup(getContext());
        sourceGroup.setSingleSelection(true);
        sourceGroup.setSelectionRequired(true);
        sourceGroup.setSingleLine(true);
        sourceGroup.setChipSpacingHorizontal(dp(6));
        HorizontalScrollView sourceScroll = new HorizontalScrollView(getContext());
        sourceScroll.setHorizontalScrollBarEnabled(false); sourceScroll.setFillViewport(false);
        sourceScroll.addView(sourceGroup, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        LayoutParams sourceLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); sourceLp.topMargin = dp(6);
        addView(sourceScroll, sourceLp);
        renderSourceButtons();

        TextView filterTitle = label("음악 결과 필터", 12, true);
        LayoutParams ft = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); ft.topMargin = dp(12);
        addView(filterTitle, ft);
        filterGroup = new ChipGroup(getContext());
        filterGroup.setSingleLine(false);
        filterGroup.setChipSpacingHorizontal(dp(6));
        filterGroup.setChipSpacingVertical(dp(4));
        addView(filterGroup);
        TextView filterHint = label("‘공식 음원만’ 판별은 YouTube 계열에 적용되며 SoundCloud·Audius·Bandcamp는 직접 업로드 결과를 유지합니다.", 9, false);
        LayoutParams fh = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); fh.topMargin = dp(3); addView(filterHint, fh);
        renderFilterButtons();

        LinearLayout popularHeader = new LinearLayout(getContext()); popularHeader.setOrientation(HORIZONTAL); popularHeader.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams phLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); phLp.topMargin = dp(14); addView(popularHeader, phLp);
        popularHeader.addView(label("지금 많이 찾는 음악", 12, true), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        popularStatus = label("불러오는 중", 10, false); popularHeader.addView(popularStatus);
        popularRow = new LinearLayout(getContext()); popularRow.setOrientation(HORIZONTAL);
        HorizontalScrollView popularScroll = horizontal(popularRow); LayoutParams psLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)); psLp.topMargin = dp(5); addView(popularScroll, psLp);

        LinearLayout recentHeader = new LinearLayout(getContext()); recentHeader.setOrientation(HORIZONTAL); recentHeader.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams rhLp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); rhLp.topMargin = dp(10); addView(recentHeader, rhLp);
        recentHeader.addView(label("최근 검색어", 12, true), new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        TextView clear = label("지우기", 10, false); clear.setPadding(dp(10), dp(4), dp(2), dp(4)); clear.setOnClickListener(v -> prefs.edit().remove(YoutubeRepository.KEY_RECENT_SEARCHES).apply()); recentHeader.addView(clear);
        recentRow = new LinearLayout(getContext()); recentRow.setOrientation(HORIZONTAL);
        HorizontalScrollView recentScroll = horizontal(recentRow); LayoutParams rsLp = new LayoutParams(LayoutParams.MATCH_PARENT, dp(44)); rsLp.topMargin = dp(4); addView(recentScroll, rsLp);

        renderRecentSearches(); loadPopularTerms();
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext()); scroll.setHorizontalScrollBarEnabled(false); scroll.setFillViewport(false);
        scroll.addView(row, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)); return scroll;
    }

    private void renderSourceButtons() {
        sourceGroup.removeAllViews();
        String selected = prefs.getString(YoutubeRepository.KEY_SEARCH_SOURCE, "music_first");
        addSourceChip("음악 우선", "music_first", selected);
        addSourceChip("통합", "all", selected);
        addSourceChip("YouTube Music", "youtube_music", selected);
        addSourceChip("YouTube", "youtube", selected);
        addSourceChip("SoundCloud", "soundcloud", selected);
        addSourceChip("Audius", "audius", selected);
        addSourceChip("Bandcamp", "bandcamp", selected);
    }

    private void addSourceChip(String label, String value, String selected) {
        Chip chip = baseChip(label); chip.setCheckable(true); chip.setChecked(value.equals(selected));
        chip.setOnClickListener(v -> prefs.edit().putString(YoutubeRepository.KEY_SEARCH_SOURCE, value).apply()); sourceGroup.addView(chip);
    }

    private void renderFilterButtons() {
        filterGroup.removeAllViews();
        addToggleChip("공식 음원만", YoutubeRepository.KEY_FILTER_OFFICIAL, false);
        addToggleChip("라이브 제외", YoutubeRepository.KEY_FILTER_EXCLUDE_LIVE, true);
        addToggleChip("커버 제외", YoutubeRepository.KEY_FILTER_EXCLUDE_COVER, true);
        addToggleChip("리믹스 포함", YoutubeRepository.KEY_FILTER_INCLUDE_REMIX, false);
    }

    private void addToggleChip(String title, String key, boolean defaultValue) {
        Chip chip = baseChip(title); chip.setCheckable(true); chip.setChecked(prefs.getBoolean(key, defaultValue));
        chip.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean(key, checked).apply()); filterGroup.addView(chip);
    }

    private Chip baseChip(String title) {
        Chip chip = new Chip(getContext()); chip.setText(title); chip.setTextSize(11f); chip.setEnsureMinTouchTargetSize(false);
        chip.setChipMinHeight(dp(34)); chip.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
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
                for (SearchResult item : list) { if (item.title != null && !item.title.trim().isEmpty()) unique.add(item.title.trim()); if (unique.size() >= 10) break; }
                terms.addAll(unique);
            } catch (Exception ignored) { }
            post(() -> renderPopularTerms(terms));
        });
    }

    private void renderPopularTerms(List<String> terms) {
        popularRow.removeAllViews();
        if (terms.isEmpty()) { popularStatus.setText("차트 연결 안 됨"); popularRow.addView(label("최근 검색어를 이용해 주세요", 11, false)); return; }
        popularStatus.setText(terms.size() + "개"); int rank = 1;
        for (String term : terms) { Chip chip = baseChip(rank + "  " + term); chip.setOnClickListener(v -> search(term)); popularRow.addView(chip); rank++; }
    }

    private void renderRecentSearches() {
        recentRow.removeAllViews();
        try {
            JSONArray array = new JSONArray(prefs.getString(YoutubeRepository.KEY_RECENT_SEARCHES, "[]"));
            if (array.length() == 0) { recentRow.addView(label("아직 검색 기록이 없습니다", 11, false)); return; }
            for (int i = 0; i < array.length(); i++) {
                String term = array.optString(i).trim(); if (term.isEmpty()) continue;
                Chip chip = baseChip(term); chip.setOnClickListener(v -> search(term)); recentRow.addView(chip);
            }
        } catch (Exception e) { recentRow.addView(label("아직 검색 기록이 없습니다", 11, false)); }
    }

    private void search(String term) {
        View root = getRootView(); EditText input = root.findViewById(R.id.etSearch); View button = root.findViewById(R.id.btnSearch);
        if (input == null || button == null) return; input.setText(term); input.setSelection(input.getText().length()); button.performClick();
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(text); view.setTextSize(sp); view.setTextColor(ContextCompat.getColor(getContext(), bold ? R.color.text_primary : R.color.muted));
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD); return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); prefs.registerOnSharedPreferenceChangeListener(this); renderSourceButtons(); renderFilterButtons(); renderRecentSearches(); }
    @Override protected void onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(this); io.shutdownNow(); super.onDetachedFromWindow(); }
    @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (YoutubeRepository.KEY_SEARCH_SOURCE.equals(key)) renderSourceButtons();
        if (YoutubeRepository.KEY_RECENT_SEARCHES.equals(key)) renderRecentSearches();
    }
}
