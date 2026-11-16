package tech.tachion.inkios.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import tech.tachion.inkios.eink.Eink;

public final class InkField {
    public interface Submit { void onSubmit(String text); }

    private final Activity activity;
    private final FrameLayout host;
    private Field field;
    private View scrim;

    public InkField(Activity activity, FrameLayout host) {
        this.activity = activity;
        this.host = host;
    }

    public boolean isShown() { return field != null; }

    public void show(View anchor, RectF r, String text, String hint, boolean url, final Submit submit) {
        hide();
        Theme t = Theme.get(activity);
        field = new Field(activity);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.PAPER);
        bg.setStroke(Math.max(2, t.pxi(1.5f)), Theme.INK);
        bg.setCornerRadius(Math.min(r.height() / 2f, t.px(12)));
        field.setBackgroundDrawable(bg);
        field.setPadding(t.pxi(14), 0, t.pxi(14), 0);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, t.px(17));
        field.setTypeface(t.textMedium);
        field.setTextColor(Theme.INK);
        field.setHintTextColor(Theme.TERTIARY);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | (url ? InputType.TYPE_TEXT_VARIATION_URI : 0));
        field.setImeOptions((url ? EditorInfo.IME_ACTION_GO : EditorInfo.IME_ACTION_SEARCH)
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setHint(hint);
        field.setText(text == null ? "" : text);
        field.setSelectAllOnFocus(true);
        field.onBack = this::hide;
        field.setOnEditorActionListener((v, id, ev) -> {
            String s = field.getText().toString();
            hide();
            submit.onSubmit(s);
            return true;
        });

        scrim = new View(activity);
        scrim.setOnClickListener(v -> hide());
        host.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        int[] a = new int[2], h = new int[2];
        anchor.getLocationInWindow(a);
        host.getLocationInWindow(h);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.leftMargin = a[0] - h[0] + Math.round(r.left);
        lp.topMargin = a[1] - h[1] + Math.round(r.top);
        host.addView(field, lp);
        field.requestFocus();
        final Field f = field;
        f.post(() -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(f, 0);
        });
    }

    public void hide() {
        if (field == null) return;
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(field.getWindowToken(), 0);
        host.removeView(field);
        host.removeView(scrim);
        field = null;
        scrim = null;
        host.postDelayed(() -> Eink.refresh(host, Eink.Mode.CLEAN), 350);
    }

    private static final class Field extends EditText {
        Runnable onBack;

        Field(Context c) { super(c); }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && onBack != null) {
                onBack.run();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
    }
}
