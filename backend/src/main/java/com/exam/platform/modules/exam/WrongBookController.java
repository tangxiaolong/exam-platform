package com.exam.platform.modules.exam;

import com.exam.platform.common.api.Result;
import com.exam.platform.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 错题状态维护与收藏（考生端）。
 *
 * <p>列表统一在 {@code GET /v1/wrong-questions}（{@code LearningController}），
 * 那里会把练习错题与考试错题合并返回并标注来源。这里只保留「对某道题做什么」的动作，
 * 避免出现两个都能列出错题、但数据来源不同的接口。</p>
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class WrongBookController {

    private final WrongBookService wrongBookService;

    /** 标记已掌握 / 取消掌握。仅对考试错题有效；再次答错会自动重置为未掌握。 */
    @PostMapping("/wrong-questions/{questionId}/mastered")
    public Result<Void> markMastered(@PathVariable Long questionId,
                                     @RequestParam(defaultValue = "true") boolean mastered) {
        wrongBookService.markMastered(UserContext.userId(), questionId, mastered);
        return Result.ok(null);
    }

    @DeleteMapping("/wrong-questions/{questionId}")
    public Result<Void> removeWrong(@PathVariable Long questionId) {
        wrongBookService.removeWrong(UserContext.userId(), questionId);
        return Result.ok(null);
    }

    /** 已加入错题本的题目 id 全集（成绩单页一次拉取、本地判断按钮状态，不打 N 次接口）。 */
    @GetMapping("/wrong-questions/ids")
    public Result<List<Long>> wrongIds() {
        return Result.ok(wrongBookService.wrongQuestionIds(UserContext.userId(), null));
    }

    /** 手动加入错题本：成绩单上对任意题一键加入；已存在则幂等返回成功。 */
    @PostMapping("/wrong-questions/{questionId}")
    public Result<Void> addWrong(@PathVariable Long questionId) {
        wrongBookService.addWrong(UserContext.userId(), questionId);
        return Result.ok(null);
    }

    @GetMapping("/favorites")
    public Result<List<Map<String, Object>>> pageFavorites(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(wrongBookService.pageFavorites(UserContext.userId(), pageNo, pageSize));
    }

    /** 已收藏题目 id 全集（练习页本地判断心形状态）。 */
    @GetMapping("/favorites/ids")
    public Result<List<Long>> favoriteIds() {
        return Result.ok(wrongBookService.favoriteIds(UserContext.userId()));
    }

    @PostMapping("/favorites/{questionId}")
    public Result<Void> addFavorite(@PathVariable Long questionId,
                                    @RequestParam(required = false) String remark) {
        wrongBookService.addFavorite(UserContext.userId(), questionId, remark);
        return Result.ok(null);
    }

    @DeleteMapping("/favorites/{questionId}")
    public Result<Void> removeFavorite(@PathVariable Long questionId) {
        wrongBookService.removeFavorite(UserContext.userId(), questionId);
        return Result.ok(null);
    }
}
