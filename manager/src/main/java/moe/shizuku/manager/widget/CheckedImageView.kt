package moe.shizuku.manager.widget

import android.content.Context
import android.util.AttributeSet
import android.widget.Checkable
import android.widget.ImageView

class CheckedImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : ImageView(context, attrs, defStyleAttr, defStyleRes), Checkable {

    private var mChecked = false

    override fun setChecked(checked: Boolean) {
        if (mChecked != checked) {
            mChecked = checked
            refreshDrawableState()
        }
    }

    override fun isChecked(): Boolean {
        return mChecked
    }

    override fun toggle() {
        setChecked(!mChecked)
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)
        if (isChecked) {
            mergeDrawableStates(drawableState, CHECKED_STATE_SET)
        }
        return drawableState
    }

    companion object {

        private val CHECKED_STATE_SET = intArrayOf(android.R.attr.state_checked)
    }
}
