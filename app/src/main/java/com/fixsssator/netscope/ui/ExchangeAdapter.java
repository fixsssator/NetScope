package com.fixsssator.netscope.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fixsssator.netscope.R;
import com.fixsssator.netscope.data.Exchange;
import com.fixsssator.netscope.util.Fmt;

import java.util.ArrayList;
import java.util.List;

final class ExchangeAdapter extends RecyclerView.Adapter<ExchangeAdapter.VH> {
    interface OnClick {
        void onClick(Exchange e);
    }

    private static final int GREEN = Color.parseColor("#2E9E4F");
    private static final int BLUE = Color.parseColor("#2979FF");
    private static final int ORANGE = Color.parseColor("#E08A00");
    private static final int RED = Color.parseColor("#D93025");
    private static final int GRAY = Color.parseColor("#8A8F98");

    private List<Exchange> items = new ArrayList<>();
    private final OnClick listener;

    ExchangeAdapter(OnClick listener) {
        this.listener = listener;
    }

    void submit(List<Exchange> list) {
        items = list;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_exchange, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Exchange e = items.get(position);
        h.method.setText(e.isTunnel() ? "TUNNEL" : e.websocket ? "WS" : e.method);

        String path = e.isTunnel() ? "" : e.path;
        h.title.setText(e.host + path);

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
        h.itemView.setOnClickListener(v -> listener.onClick(e));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView method;
        final TextView title;
        final TextView sub;

        VH(@NonNull View v) {
            super(v);
            method = v.findViewById(R.id.method);
            title = v.findViewById(R.id.title);
            sub = v.findViewById(R.id.sub);
        }
    }
}
