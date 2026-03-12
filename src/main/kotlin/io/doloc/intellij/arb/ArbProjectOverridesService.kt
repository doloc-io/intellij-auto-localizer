package io.doloc.intellij.arb

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

@Service(Service.Level.PROJECT)
@State(
    name = "io.doloc.intellij.arb.ArbProjectOverridesService",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class ArbProjectOverridesService(
    private val project: Project
) : PersistentStateComponent<ArbProjectOverridesService.State> {
    data class ArbTargetOverride(
        var targetFile: String = "",
        var targetLang: String = ""
    )

    data class ArbScopeOverride(
        var scopeDir: String = "",
        var baseFile: String = "",
        var sourceLang: String = "",
        var targetOverrides: MutableList<ArbTargetOverride> = mutableListOf()
    )

    data class State(
        var scopes: MutableList<ArbScopeOverride> = mutableListOf()
    )

    private var state = State()

    private var scopes: MutableList<ArbScopeOverride>
        get() = state.scopes
        set(value) {
            state = State(scopes = copyScopes(value))
        }

    override fun getState(): State = State(scopes = copyScopes(scopes))

    override fun loadState(state: State) {
        scopes = state.scopes
    }

    fun getScopeSnapshot(): List<ArbScopeOverride> {
        return scopes.map { scope ->
            scope.copy(
                targetOverrides = scope.targetOverrides.map { it.copy() }.toMutableList()
            )
        }
    }

    fun replaceScopes(newScopes: List<ArbScopeOverride>) {
        scopes = newScopes.map { scope ->
            scope.copy(targetOverrides = scope.targetOverrides.map { it.copy() }.toMutableList())
        }.toMutableList()
    }

    fun findNearestScopeOverride(startDirectory: VirtualFile?): ArbScopeOverride? {
        var current = startDirectory
        while (current != null) {
            val storedPath = toStoredPath(current.path)
            scopes.firstOrNull { it.scopeDir == storedPath }?.let { return it }
            current = current.parent
        }
        return null
    }

    fun saveScopeOverride(scopeDir: VirtualFile, baseFile: VirtualFile, sourceLang: String?) {
        val scope = getOrCreateScope(scopeDir)
        scope.baseFile = toStoredPath(baseFile.path)
        scope.sourceLang = sourceLang.orEmpty()
    }

    fun saveTargetLanguage(scopeDir: VirtualFile, targetFile: VirtualFile, targetLang: String) {
        val scope = getOrCreateScope(scopeDir)
        val storedTarget = toStoredPath(targetFile.path)
        val existing = scope.targetOverrides.firstOrNull { it.targetFile == storedTarget }
        if (existing != null) {
            existing.targetLang = targetLang
        } else {
            scope.targetOverrides += ArbTargetOverride(storedTarget, targetLang)
        }
    }

    fun resolveBaseFile(scope: ArbScopeOverride?): VirtualFile? {
        val storedPath = scope?.baseFile?.takeIf { it.isNotBlank() } ?: return null
        return resolveStoredFile(storedPath)
    }

    fun resolveTargetLanguage(scope: ArbScopeOverride?, targetFile: VirtualFile): String? {
        val storedTarget = toStoredPath(targetFile.path)
        return scope?.targetOverrides
            ?.firstOrNull { it.targetFile == storedTarget }
            ?.targetLang
            ?.takeIf { it.isNotBlank() }
    }

    fun clearScope(scopeDir: String) {
        scopes.removeAll { it.scopeDir == scopeDir }
    }

    fun clearTarget(scopeDir: String, targetFile: String) {
        val scope = scopes.firstOrNull { it.scopeDir == scopeDir } ?: return
        scope.targetOverrides.removeAll { it.targetFile == targetFile }
        if (scope.baseFile.isBlank() && scope.sourceLang.isBlank() && scope.targetOverrides.isEmpty()) {
            scopes.remove(scope)
        }
    }

    fun toStoredPath(path: String): String {
        val normalizedPath = FileUtil.toSystemIndependentName(path)
        val projectBasePath = project.basePath?.let(FileUtil::toSystemIndependentName) ?: return normalizedPath
        val isWithinProject = normalizedPath == projectBasePath || normalizedPath.startsWith("$projectBasePath/")
        return if (isWithinProject) {
            FileUtil.getRelativePath(projectBasePath, normalizedPath, '/') ?: normalizedPath
        } else {
            normalizedPath
        }
    }

    fun resolveStoredFile(storedPath: String): VirtualFile? {
        return resolveStoredPath(storedPath)?.takeIf { !it.isDirectory }
    }

    fun resolveStoredDirectory(storedPath: String): VirtualFile? {
        return resolveStoredPath(storedPath)?.takeIf { it.isDirectory }
    }

    fun resolveStoredPath(storedPath: String): VirtualFile? {
        val normalizedPath = FileUtil.toSystemIndependentName(storedPath)
        val absolutePath = if (File(normalizedPath).isAbsolute) {
            normalizedPath
        } else {
            val basePath = project.basePath?.let(FileUtil::toSystemIndependentName) ?: return null
            "$basePath/$normalizedPath"
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(absolutePath).canonicalFile)
    }

    private fun getOrCreateScope(scopeDir: VirtualFile): ArbScopeOverride {
        val storedScope = toStoredPath(scopeDir.path)
        return scopes.firstOrNull { it.scopeDir == storedScope } ?: ArbScopeOverride(scopeDir = storedScope)
            .also { scopes += it }
    }

    private fun copyScopes(source: List<ArbScopeOverride>): MutableList<ArbScopeOverride> {
        return source.map { scope ->
            scope.copy(targetOverrides = scope.targetOverrides.map { it.copy() }.toMutableList())
        }.toMutableList()
    }

    companion object {
        fun getInstance(project: Project): ArbProjectOverridesService = project.service()
    }
}
