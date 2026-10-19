package moe.shizuku.manager.management

import android.content.Context
import android.content.pm.PackageInfo
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.authorization.AuthorizationManager
import rikka.lifecycle.Resource
import rikka.lifecycle.viewModels

@MainThread
fun ComponentActivity.appsViewModel() = viewModels { AppsViewModel(this) }

class AppsViewModel(context: Context) : ViewModel() {

    private val _packages = MutableLiveData<Resource<List<PackageInfo>>>()
    val packages = _packages as LiveData<Resource<List<PackageInfo>>>

    private val _grantedCount = MutableLiveData<Resource<Int>>()
    val grantedCount = _grantedCount as LiveData<Resource<Int>>

    fun load(onlyCount: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val list: MutableList<PackageInfo> = ArrayList()
                var count = 0
                for (pi in AuthorizationManager.getPackages()) {
                    val appInfo = pi.applicationInfo ?: continue
                    list.add(pi)
                    if (AuthorizationManager.granted(pi.packageName, appInfo.uid)) count++
                }
                if (!onlyCount) _packages.postValue(Resource.success(list))
                _grantedCount.postValue(Resource.success(count))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _packages.postValue(Resource.error(e, null))
                _grantedCount.postValue(Resource.error(e, 0))
            }
        }
    }
}
