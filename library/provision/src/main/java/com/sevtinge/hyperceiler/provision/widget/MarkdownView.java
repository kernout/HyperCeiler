package com.sevtinge.hyperceiler.provision.widget;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BulletSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.sevtinge.hyperceiler.provision.R;
import com.sevtinge.hyperceiler.provision.utils.LocalTerms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fan.internal.utils.AttributeResolver;

public class MarkdownView extends TextView {

    private OnMarkdownLoadListener loadListener;

    // 样式配置
    private static int COLOR_TITLE = Color.parseColor("#333333");
    private static int COLOR_TEXT = Color.parseColor("#444444");
    private static final int COLOR_LINK = Color.parseColor("#1A73E8");
    private static final int COLOR_BULLET = Color.parseColor("#888888");
    private static final int COLOR_DIVIDER = Color.parseColor("#CCCCCC");

    // 定义回调接口
    public interface OnMarkdownLoadListener {
        void onResult(boolean success);
    }

    public void setOnMarkdownLoadListener(OnMarkdownLoadListener listener) {
        this.loadListener = listener;
    }

    public MarkdownView(Context context, AttributeSet attrs) {
        super(context, attrs);
        COLOR_TITLE = AttributeResolver.resolveColor(context, fan.appcompat.R.attr.textColorList);
        COLOR_TEXT = AttributeResolver.resolveColor(context, fan.appcompat.R.attr.textColorListSecondary);
        init();
    }

    private void init() {
        this.setTextSize(16);
        this.setLineSpacing(0, 1.6f);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        this.setPadding(padding, padding, padding, padding);
        this.setMovementMethod(LinkMovementMethod.getInstance());
        this.setHighlightColor(Color.TRANSPARENT);
    }

    /** Load the exact bundled legal document; never fall back to a network request. */
    public void loadMarkdownFromAsset(String assetPath) {
        try {
            if (!LocalTerms.PROTOCOL_ASSET.equals(assetPath)
                && !LocalTerms.PRIVACY_ASSET.equals(assetPath)) {
                throw new IOException("Unknown legal asset");
            }
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getContext().getAssets().open(assetPath), StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, count);
                }
            }
            if (content.length() == 0) throw new IOException("Empty legal asset");
            render(content.toString());
            if (loadListener != null) loadListener.onResult(true);
        } catch (IOException e) {
            setText(R.string.provision_local_terms_load_failed);
            if (loadListener != null) loadListener.onResult(false);
        }
    }

    public void render(String rawText) {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        String[] lines = rawText.split("\\r?\\n");

        for (String line : lines) {
            String trimmedLine = line.trim();
            int start = ssb.length();

            // 1. 分割线渲染 (---)
            if (trimmedLine.length() >= 3 && trimmedLine.replaceAll("[-*_ ]", "").isEmpty()) {
                ssb.append(" \n");
                ssb.setSpan(new HorizontalLineSpan(COLOR_DIVIDER), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.append("\n");
                continue;
            }

            // 2. 标题渲染 (## )
            if (line.startsWith("## ")) {
                String content = line.substring(3).trim();
                ssb.append(content).append("\n\n");
                int end = ssb.length() - 1;
                ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new AbsoluteSizeSpan(20, true), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new ForegroundColorSpan(COLOR_TITLE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // 3. 列表项 (- )
            else if (trimmedLine.startsWith("- ")) {
                renderListItem(ssb, line, start);
            }
            // 4. 普通文本
            else {
                if (trimmedLine.isEmpty()) {
                    ssb.append("\n");
                } else {
                    ssb.append(line).append("\n");
                    ssb.setSpan(new ForegroundColorSpan(COLOR_TEXT), start, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    processInlineStyles(ssb, start, ssb.length());
                }
            }
        }
        setText(ssb);
    }

    private void renderListItem(SpannableStringBuilder ssb, String line, int start) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        String content = line.trim().substring(2);
        ssb.append(content).append("\n");
        int end = ssb.length();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            ssb.setSpan(new BulletSpan(30, COLOR_BULLET, 6), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            ssb.setSpan(new BulletSpan(30), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        ssb.setSpan(new LeadingMarginSpan.Standard(30 + (indent / 2 * 40)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        processInlineStyles(ssb, start, end);
    }

    private void processInlineStyles(SpannableStringBuilder ssb, int start, int end) {
        String sub = ssb.subSequence(start, end).toString();
        // 链接
        Pattern lp = Pattern.compile("\\[(.*?)\\]\\((.*?)\\)");
        Matcher lm = lp.matcher(sub);
        List<Object[]> links = new ArrayList<>();
        while (lm.find()) links.add(new Object[]{lm.start(), lm.end(), lm.group(1), lm.group(2)});
        for (int i = links.size() - 1; i >= 0; i--) {
            Object[] info = links.get(i);
            int mS = start + (int)info[0], mE = start + (int)info[1];
            ssb.replace(mS, mE, (String)info[2]);
            ssb.setSpan(new CustomClickableSpan((String)info[3]), mS, mS + ((String)info[2]).length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // 加粗
        sub = ssb.subSequence(start, ssb.length()).toString();
        Pattern bp = Pattern.compile("\\*\\*(.*?)\\*\\*");
        Matcher bm = bp.matcher(sub);
        List<int[]> bolds = new ArrayList<>();
        while (bm.find()) bolds.add(new int[]{bm.start(), bm.end()});
        for (int i = bolds.size() - 1; i >= 0; i--) {
            int[] r = bolds.get(i);
            int bS = start + r[0], bE = start + r[1];
            ssb.setSpan(new StyleSpan(Typeface.BOLD), bS, bE, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ssb.delete(bE - 2, bE); ssb.delete(bS, bS + 2);
        }
    }

    private record HorizontalLineSpan(int color) implements LeadingMarginSpan {
        @Override
        public int getLeadingMargin(boolean first) {
            return 0;
        }

        @Override
        public void drawLeadingMargin(Canvas c, Paint p, int x, int dir, int top, int bsl, int bot, CharSequence txt, int s, int e, boolean f, Layout l) {
                int old = p.getColor();
            p.setColor(color);
                c.drawRect(x, (top + bot) / 2f - 2f, x + l.getWidth(), (top + bot) / 2f + 2f, p);
                p.setColor(old);
            }
        }

    private class CustomClickableSpan extends ClickableSpan {
        private final String url;
        CustomClickableSpan(String url) { this.url = url; }

        @Override
        public void onClick(@NonNull View w) {
            try {
                getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception ignored) {}
        }

        @Override
        public void updateDrawState(@NonNull TextPaint ds) {
            super.updateDrawState(ds); ds.setColor(COLOR_LINK); ds.setUnderlineText(true);
        }
    }
}
