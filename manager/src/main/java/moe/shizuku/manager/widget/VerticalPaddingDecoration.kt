package moe.shizuku.manager.widget

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

class VerticalPaddingDecoration @JvmOverloads constructor(context: Context, paddingDp: Int = 8) : RecyclerView.ItemDecoration() {

    private var mAllowTop = true
    private var mAllowBottom = true
    private val mPadding = Math.round(paddingDp * context.resources.displayMetrics.density)

    fun setAllowTop(allowTop: Boolean) {
        mAllowTop = allowTop
    }

    fun setAllowBottom(allowBottom: Boolean) {
        mAllowBottom = allowBottom
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val adapter = parent.adapter ?: return
        val position = parent.getChildAdapterPosition(view)
        val count = adapter.itemCount
        if (position == 0 && mAllowTop) {
            outRect.top = mPadding
        } else if (position == count - 1 && mAllowBottom) {
            outRect.bottom = mPadding
        }
    }
}
