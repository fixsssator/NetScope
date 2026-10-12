package com.fixsssator.netscope.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.util.Fmt;

import java.util.ArrayList;
import java.util.List;

/** Shows either plain request rows or group headers followed by their requests. */
final class ExchangeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    interface Callbacks {
        void onItemClick(Exchange e);

        void onItemLongClick(Exchange e);

        void onGroupClick(String key);

        void onGroupLongClick(String key);
    }

    static final class Row {
        final Exchange ex;       // null for group headers
        final String groupKey;   // set for headers and for items inside a group
        final String title;
        final int count;
        final boolean collapsed;
        final boolean checked;

        private Row(Exchange ex, String groupKey, String title, int count, boolean collapsed, boolean checked) {
            this.ex = ex;
            this.groupKey = groupKey;
            this.title = title;
            this.count = count;
            this.collapsed = collapsed;
            this.checked = checked;
        }

        static Row item(Exchange e, String groupKey, boolean checked) {
            return new Row(e, groupKey, null, 0, false, checked);
        }

        static Row group(String key, String title, int count, boolean collapsed, boolean checked) {
            return new Row(null, key, title, count, collapsed, checked);
        }
    }

    private static final int TYPE_ITEM = 0;
    private static final int TYPE_GROUP = 1;

    private static final int GREEN = Color.parseColor("#2E9E4F");
    private static final int BLUE = Color.parseColor("#2979FF");
    private static final int ORANGE = Color.parseColor("#E08A00");
    private static final int RED = Color.parseColor("#D93025");
    private static final int GRAY = Color.parseColor("#8A8F98");

    private List<Row> rows = new ArrayList<>();
    private boolean selecting;
    private final Callbacks cb;

    ExchangeAdapter(Callbacks cb) {
        this.cb = cb;
    }

    void submit(List<Row> list, boolean selecting) {
        this.rows = list;
        this.selecting = selecting;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position).ex == null ? TYPE_GROUP : TYPE_ITEM;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GROUP) return new GroupVH(inf.inflate(R.layout.item_group, parent, false));
        return new ItemVH(inf.inflate(R.layout.item_exchange, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row r = rows.get(position);
        if (holder instanceof GroupVH) {
            GroupVH h = (GroupVH) holder;
            h.arrow.setText(r.collapsed ? "▸" : "▾");
            h.title.setText(r.title);
            h.count.setText(String.valueOf(r.count));
            h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            h.check.setChecked(r.checked);
            h.itemView.setOnClickListener(v -> cb.onGroupClick(r.groupKey));
            h.itemView.setOnLongClickListener(v -> {
                cb.onGroupLongClick(r.groupKey);
                return true;
            });
            return;
        }
        ItemVH h = (ItemVH) holder;
        Exchange e = r.ex;
        float d = h.itemView.getResources().getDisplayMetrics().density;
        h.itemView.setPadding((int) ((r.groupKey != null ? 28 : 12) * d), h.itemView.getPaddingTop(),
                h.itemView.getPaddingRight(), h.itemView.getPaddingBottom());
        h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        h.check.setChecked(r.checked);
        h.method.setText(e.isTunnel() ? "TUNNEL" : e.websocket ? "WS" : e.method);
        h.title.setText(e.host + (e.isTunnel() ? "" : e.path));

        StringBuilder sub = new StringBuilder();
        int color;
        switch (e.state) {
            case PENDING:
                sub.append("pending…");
                color = GRAY;
                break;
            case ERROR:
                sub.append("error");
                color = RED;
                break;
            case BLOCKED:
                sub.append("blocked");
                color = ORANGE;
                break;
            case MOCKED:
                sub.append("mocked ").append(e.status);
                color = BLUE;
                break;
            default:
                sub.append(e.status).append(' ').append(e.statusText);
                color = e.status >= 500 ? RED : e.status >= 400 ? ORANGE : e.status >= 300 ? BLUE : GREEN;
        }
        sub.append(" · ").append(Fmt.duration(e.durationMs));
        if (!e.isTunnel()) sub.append(" · ").append(Fmt.size(e.respBodySize));
        if (e.intercepted) sub.append(" · TLS");
        if (e.appLabel != null) sub.append(" · ").append(e.appLabel);
        h.sub.setText(sub.toString().trim());
        h.sub.setTextColor(color);
        h.method.setTextColor(color);
        h.itemView.setOnClickListener(v -> cb.onItemClick(e));
        h.itemView.setOnLongClickListener(v -> {
            cb.onItemLongClick(e);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static final class ItemVH extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView method;
        final TextView title;
        final TextView sub;

        ItemVH(@NonNull View v) {
            super(v);
            check = v.findViewById(R.id.check);
            method = v.findViewById(R.id.method);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
        }
    }

    static final class GroupVH extends RecyclerView.ViewHolder {
        final CheckBox check;
        final TextView arrow;
        final TextView title;
        final TextView count;

        GroupVH(@NonNull View v) {
            super(v);
            check = v.findViewById(R.id.check);
            arrow = v.findViewById(R.id.arrow);
            title = v.findViewById(R.id.title);
            count = v.findViewById(R.id.count);
        }
    }
}
