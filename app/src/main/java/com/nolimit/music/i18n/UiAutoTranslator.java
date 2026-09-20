package com.nolimit.music.i18n;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.nolimit.music.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Broad UI localization layer for No Limit Music.
 *
 * Korean remains the authored source UI. For a selected non-Korean language,
 * ML Kit downloads one on-device translation model and translates UI chrome
 * only. Music titles, artists, lyrics and RecyclerView content are excluded.
 *
 * Supported languages follow ML Kit Translation's current Android list.
 */
public final class UiAutoTranslator {
    private static final String PREFS = "settings";
    public static final String KEY_LANGUAGE = "app_language";
    private static final String SYSTEM = "system";

    private static final List<String> SUPPORTED = Collections.unmodifiableList(Arrays.asList(
            "af","ar","be","bg","bn","ca","cs","cy","da","de","el","en","eo","es","et","fa","fi","fr",
            "ga","gl","gu","he","hi","hr","ht","hu","id","is","it","ja","ka","kn","ko","lt","lv","mk",
            "mr","ms","mt","nl","no","pl","pt","ro","ru","sk","sl","sq","sv","sw","ta","te","th","tl",
            "tr","uk","ur","vi","zh"
    ));

    private static final Set<String> UI_HINTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "홈","검색","설정","플레이리스트","다운로드","백업","재생","저장","대기열","라이브러리",
            "가사","수면","노래","음악","곡","허용","취소","확인","삭제","추가","불러오는 중","실패",
            "완료","복원","아티스트","앨범","좋아요","차트","모바일 데이터","화면 테마","라이트","다크",
            "찾기","인식","이미지","스크린샷","스마트","최근","자동","공용","만들기","이름","오류",
            "듣는 중","분석 중","준비","테스트판 안내","사용","시간","분","초","언어","번역"
    )));

    private static final WeakHashMap<Activity, Session> SESSIONS = new WeakHashMap<>();
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    private UiAutoTranslator() {}

    public static List<String> supportedLanguageCodes() {
        return SUPPORTED;
    }

    public static String selectedLanguageTag(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_LANGUAGE, SYSTEM);
        if (saved == null || saved.trim().isEmpty() || SYSTEM.equals(saved)) {
            String systemTag = Locale.getDefault().getLanguage();
            return normalizeSupported(systemTag);
        }
        return normalizeSupported(saved);
    }

    public static String currentLanguageName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_LANGUAGE, SYSTEM);
        if (saved == null || SYSTEM.equals(saved)) {
            Locale current = Locale.getDefault();
            String nativeName = current.getDisplayLanguage(current);
            return "시스템 · " + capitalize(nativeName);
        }
        Locale locale = Locale.forLanguageTag(saved);
        String nativeName = locale.getDisplayLanguage(locale);
        return capitalize(nativeName.isEmpty() ? saved : nativeName);
    }

    public static void showLanguagePicker(Activity activity) {
        List<LanguageChoice> choices = new ArrayList<>();
        choices.add(new LanguageChoice(SYSTEM, "시스템 언어 · " + capitalize(Locale.getDefault().getDisplayLanguage(Locale.getDefault()))));
        for (String code : SUPPORTED) {
            Locale l = Locale.forLanguageTag(code);
            String nativeName = l.getDisplayLanguage(l);
            String englishName = l.getDisplayLanguage(Locale.ENGLISH);
            String label = capitalize(nativeName.isEmpty() ? code : nativeName);
            if (!englishName.isEmpty() && !englishName.equalsIgnoreCase(nativeName)) label += " · " + englishName;
            choices.add(new LanguageChoice(code, label));
        }
        choices.subList(1, choices.size()).sort(Comparator.comparing(a -> a.label, String.CASE_INSENSITIVE_ORDER));

        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String selected = prefs.getString(KEY_LANGUAGE, SYSTEM);
        int checked = 0;
        String[] labels = new String[choices.size()];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label;
            if (choices.get(i).code.equals(selected)) checked = i;
        }

        new AlertDialog.Builder(activity)
                .setTitle("언어 · Language")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    LanguageChoice choice = choices.get(which);
                    prefs.edit().putString(KEY_LANGUAGE, choice.code).apply();
                    dialog.dismiss();
                    detach(activity);
                    activity.recreate();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    public static synchronized void attach(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        String target = selectedLanguageTag(activity);
        if ("ko".equals(target)) {
            detach(activity);
            return;
        }
        Session existing = SESSIONS.get(activity);
        if (existing != null && target.equals(existing.targetLanguage)) {
            existing.scheduleScan(80L);
            return;
        }
        detach(activity);
        String mlTarget = TranslateLanguage.fromLanguageTag(target);
        if (mlTarget == null) mlTarget = TranslateLanguage.ENGLISH;

        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.KOREAN)
                .setTargetLanguage(mlTarget)
                .build();
        Translator translator = Translation.getClient(options);
        Session session = new Session(activity, target, translator);
        SESSIONS.put(activity, session);
        session.start();
    }

    public static synchronized void detach(Activity activity) {
        Session s = SESSIONS.remove(activity);
        if (s != null) s.close();
    }

    private static String normalizeSupported(String tag) {
        if (tag == null || tag.trim().isEmpty()) return "en";
        String base = tag.toLowerCase(Locale.ROOT).split("[-_]")[0];
        return SUPPORTED.contains(base) ? base : "en";
    }

    private static boolean containsHangul(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    private static boolean looksLikeUi(String text) {
        if (!containsHangul(text)) return false;
        if (text.length() > 220) return false;
        for (String hint : UI_HINTS) if (text.contains(hint)) return true;
        return false;
    }

    private static boolean shouldSkip(TextView v) {
        if (v == null) return true;
        Object tag = v.getTag();
        if ("no_translate".equals(tag)) return true;
        int id = v.getId();
        if (id == R.id.tvPlayerTitle || id == R.id.tvPlayerArtist ||
                id == R.id.tvNowPlaying || id == R.id.tvNowArtist ||
                id == R.id.tvSubtitlePrevious || id == R.id.tvSubtitleCurrent || id == R.id.tvSubtitleNext) return true;

        View p = v;
        while (p != null) {
            if (p instanceof RecyclerView) return true;
            if (p.getId() == R.id.fullLyricsContainer) return true;
            if (!(p.getParent() instanceof View)) break;
            p = (View) p.getParent();
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }

    private static final class LanguageChoice {
        final String code;
        final String label;
        LanguageChoice(String code, String label) { this.code = code; this.label = label; }
    }

    private static final class Session implements ViewTreeObserver.OnGlobalLayoutListener {
        final Activity activity;
        final String targetLanguage;
        final Translator translator;
        final Handler handler = new Handler(Looper.getMainLooper());
        final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
        final WeakHashMap<TextView, String> originalText = new WeakHashMap<>();
        final WeakHashMap<TextView, String> originalHint = new WeakHashMap<>();
        boolean ready;
        boolean closed;
        boolean scanScheduled;

        Session(Activity activity, String targetLanguage, Translator translator) {
            this.activity = activity;
            this.targetLanguage = targetLanguage;
            this.translator = translator;
        }

        void start() {
            View root = activity.getWindow().getDecorView();
            root.getViewTreeObserver().addOnGlobalLayoutListener(this);
            DownloadConditions conditions = new DownloadConditions.Builder().build();
            translator.downloadModelIfNeeded(conditions)
                    .addOnSuccessListener(v -> {
                        if (closed) return;
                        ready = true;
                        scheduleScan(0L);
                    })
                    .addOnFailureListener(e -> {
                        // Keep the original Korean UI if a model cannot be downloaded.
                        ready = false;
                    });
        }

        @Override public void onGlobalLayout() {
            scheduleScan(220L);
        }

        void scheduleScan(long delay) {
            if (closed || !ready || scanScheduled) return;
            scanScheduled = true;
            handler.postDelayed(() -> {
                scanScheduled = false;
                if (!closed) scan(activity.getWindow().getDecorView());
            }, delay);
        }

        void scan(View view) {
            if (closed || view == null) return;
            if (view instanceof TextView) translateView((TextView) view);
            if (view instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) view;
                for (int i = 0; i < g.getChildCount(); i++) scan(g.getChildAt(i));
            }
        }

        void translateView(TextView v) {
            if (shouldSkip(v)) return;

            if (v instanceof EditText) {
                CharSequence hint = v.getHint();
                if (hint != null) translateHint(v, hint.toString());
                return;
            }

            CharSequence text = v.getText();
            if (text != null) translateText(v, text.toString());
            CharSequence hint = v.getHint();
            if (hint != null) translateHint(v, hint.toString());
        }

        void translateText(TextView view, String current) {
            if (current == null || current.trim().isEmpty()) return;
            String source = originalText.get(view);
            if (source == null) {
                if (!looksLikeUi(current)) return;
                source = current;
                originalText.put(view, source);
            } else if (current.equals(source)) {
                // A dynamic UI status returned to a new Korean source string.
                if (looksLikeUi(current)) originalText.put(view, current);
            } else if (!containsHangul(current)) {
                return; // already translated or non-Korean content
            } else if (looksLikeUi(current)) {
                source = current;
                originalText.put(view, source);
            } else {
                return;
            }
            final String src = source;
            translate(src, translated -> {
                if (closed || view.getWindowToken() == null) return;
                String tracked = originalText.get(view);
                if (src.equals(tracked)) view.setText(translated);
            });
        }

        void translateHint(TextView view, String current) {
            if (current == null || current.trim().isEmpty()) return;
            String source = originalHint.get(view);
            if (source == null) {
                if (!looksLikeUi(current)) return;
                source = current;
                originalHint.put(view, source);
            } else if (!containsHangul(current)) {
                return;
            } else if (looksLikeUi(current)) {
                source = current;
                originalHint.put(view, source);
            } else {
                return;
            }
            final String src = source;
            translate(src, translated -> {
                if (closed || view.getWindowToken() == null) return;
                String tracked = originalHint.get(view);
                if (src.equals(tracked)) view.setHint(translated);
            });
        }

        void translate(String source, Result callback) {
            String key = targetLanguage + "\n" + source;
            String cached = CACHE.get(key);
            if (cached != null) {
                callback.onResult(cached);
                return;
            }
            if (!pending.add(key)) return;
            translator.translate(source)
                    .addOnSuccessListener(out -> {
                        pending.remove(key);
                        if (out != null && !out.trim().isEmpty()) {
                            CACHE.put(key, out);
                            callback.onResult(out);
                        }
                    })
                    .addOnFailureListener(e -> pending.remove(key));
        }

        void close() {
            closed = true;
            try {
                View root = activity.getWindow().getDecorView();
                if (root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
            } catch (Throwable ignored) {}
            handler.removeCallbacksAndMessages(null);
            try { translator.close(); } catch (Throwable ignored) {}
        }
    }

    private interface Result { void onResult(String translated); }
}
