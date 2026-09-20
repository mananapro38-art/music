package com.nolimit.music;

import android.content.ComponentName;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.nolimit.music.ads.StartioBannerFactory;
import com.nolimit.music.playback.PlaybackService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class QueueActivity extends AppCompatActivity {
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController controller;
    private QueueAdapter adapter;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode("light".equals(prefs.getString("theme", "dark"))
                ? AppCompatDelegate.MODE_NIGHT_NO : AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.app_bg));
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹", 32, true); back.setGravity(Gravity.CENTER); back.setOnClickListener(v -> finish());
        TextView title = label("재생 대기열", 23, true);
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        header.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(header);
        status = label("플레이어 연결 중…", 11, false); root.addView(status);
        StartioBannerFactory.append(this, root, "queue");
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new QueueAdapter();
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(RecyclerView rv, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
                int from = source.getBindingAdapterPosition();
                int to = target.getBindingAdapterPosition();
                if (controller == null || from < 0 || to < 0) return false;
                controller.moveMediaItem(from, to);
                adapter.move(from, to);
                return true;
            }
            @Override public void onSwiped(RecyclerView.ViewHolder holder, int direction) {
                int pos = holder.getBindingAdapterPosition();
                if (controller != null && pos >= 0 && pos < controller.getMediaItemCount()) controller.removeMediaItem(pos);
                refresh();
            }
        });
        helper.attachToRecyclerView(list);
        connect();
    }

    private void connect() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                controller.addListener(new androidx.media3.common.Player.Listener() {
                    @Override public void onTimelineChanged(androidx.media3.common.Timeline timeline, int reason) { refresh(); }
                    @Override public void onMediaItemTransition(MediaItem mediaItem, int reason) { refresh(); }
                });
                refresh();
            } catch (Exception e) { status.setText("플레이어 연결 실패"); }
        }, ContextCompat.getMainExecutor(this));
    }

    private void refresh() {
        if (controller == null) return;
        List<MediaItem> items = new ArrayList<>();
        for (int i = 0; i < controller.getMediaItemCount(); i++) items.add(controller.getMediaItemAt(i));
        adapter.submit(items, controller.getCurrentMediaItemIndex());
        status.setText(items.size() + "곡 · 길게 끌어서 순서 변경 · 좌우 밀기 삭제");
    }

    private final class QueueAdapter extends RecyclerView.Adapter<QueueHolder> {
        private final List<MediaItem> items = new ArrayList<>();
        private int current = -1;
        void submit(List<MediaItem> values, int current) { items.clear(); items.addAll(values); this.current = current; notifyDataSetChanged(); }
        void move(int from, int to) { if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return; Collections.swap(items, from, to); notifyItemMoved(from, to); }
        @Override public QueueHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext()); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(14), dp(11), dp(14), dp(11)); row.setBackgroundResource(R.drawable.bg_smart_card);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2); lp.setMargins(0, dp(4), 0, dp(4)); row.setLayoutParams(lp);
            TextView t = label("", 14, true); TextView a = label("", 11, false); row.addView(t); row.addView(a); return new QueueHolder(row, t, a);
        }
        @Override public void onBindViewHolder(QueueHolder h, int position) {
            MediaItem item = items.get(position); MediaMetadata md = item.mediaMetadata;
            h.title.setText((position == current ? "▶  " : "⋮⋮  ") + (md.title == null ? "제목 없음" : md.title));
            h.artist.setText(md.artist == null ? "" : md.artist);
            h.itemView.setAlpha(position == current ? 1f : .72f);
            h.itemView.setOnClickListener(v -> { if (controller != null) { controller.seekToDefaultPosition(h.getBindingAdapterPosition()); controller.play(); } });
        }
        @Override public int getItemCount() { return items.size(); }
    }

    private static final class QueueHolder extends RecyclerView.ViewHolder {
        final TextView title; final TextView artist;
        QueueHolder(android.view.View itemView, TextView title, TextView artist) { super(itemView); this.title = title; this.artist = artist; }
    }

    private TextView label(String s, int sp, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(sp); v.setTextColor(ContextCompat.getColor(this, bold ? R.color.text_primary : R.color.muted)); if (bold) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD); return v; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { if (controllerFuture != null) MediaController.releaseFuture(controllerFuture); controller = null; super.onDestroy(); }
}
