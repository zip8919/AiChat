package xyz.zip8919.app.aichat;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.widget.EditText;
import android.widget.ScrollView;

/**
 * 表单页用的 ScrollView：管住多行输入框引发的整页跳动。
 *
 * <p>光标移动时系统会把「光标矩形」交给父容器，让它滚到可见位置。多行输入框
 * 自身带滚动（maxLines 限定了高度），传上来的矩形是内容坐标，外层再按控件
 * 坐标换算，两者一叠加，每次算出的滚动量都偏大一截。表现就是：在提示词框里
 * 打字或用方向键移光标，整页一段一段往下跳，最后把输入框顶出屏幕。
 *
 * <p>这里对输入框的滚动请求一律不接管，改由 {@link #showEditor} 在输入框获得
 * 焦点、键盘弹出时把整框带回视野。滚一次就够，之后光标在框内移动由输入框
 * 自己消化，页面不再跟着动。
 */
public class FormScrollView extends ScrollView {

    public FormScrollView(Context context) {
        super(context);
    }

    public FormScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FormScrollView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        // 输入框的滚动请求一律不理：见类注释
        if (child instanceof EditText) return false;
        return super.requestChildRectangleOnScreen(child, rectangle, immediate);
    }

    /**
     * 把输入框整框带回可见区域。
     *
     * <p>不调用父类的同名方法：它按「矩形是控件内容坐标」做换算，会再减一次
     * 输入框自身的滚动量，算出来的目标位置偏上，正好是这一串抖动的源头。
     * 这里直接用 {@code offsetDescendantRectToMyCoords} 拿到控件在滚动内容里的
     * 真实位置，自己决定滚多少，结果可预期。
     */
    public void showEditor(View editor) {
        if (editor == null) return;
        int w = editor.getWidth();
        int h = editor.getHeight();
        int viewH = getHeight();
        if (w <= 0 || h <= 0 || viewH <= 0) return;

        Rect r = new Rect(0, 0, w, h);
        offsetDescendantRectToMyCoords(editor, r);

        int cur = getScrollY();
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        int target = cur;
        if (r.top - pad < cur) {
            // 顶部被挡：把顶边带进来
            target = r.top - pad;
        } else if (r.bottom + pad > cur + viewH) {
            // 底部被挡：尽量把底边带进来；控件比视口高时以顶边为准
            target = Math.min(r.top - pad, r.bottom + pad - viewH);
        }
        if (target < 0) target = 0;
        int max = Math.max(0, getChildCount() == 0 ? 0
                : getChildAt(0).getBottom() - viewH);
        if (target > max) target = max;
        if (target != cur) {
            smoothScrollTo(0, target);
        }
    }
}
