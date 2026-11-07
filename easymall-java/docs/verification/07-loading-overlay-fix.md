# 步骤07补充：后台登录后加载遮罩残留

日期：2026-11-07（Asia/Shanghai）。用户反馈localhost:6002/home的数据已显示，但全屏“加载中”持续遮挡页面。

## 复现与原因边界

原请求封装只维护并发数，在归零时调用Element Plus全屏单例的close并清空引用。之前83项测试中的加载服务是替身，验证了close调用次数，没有验证真实遮罩DOM的移除。

本轮用真实管理端首页、真实Vue/Element Plus和模拟API复现：所有首页请求结束后仍有加载遮罩；重复模拟登录跳转后遮罩会累积。诊断记录显示请求计数已经归零，不能把这个症状直接判为后端登录或数据库失败。单独改成nextTick关闭仍未消除浏览器残留，因此没有将该临时方案作为最终修复。

[Element Plus官方说明](https://element-plus.org/en-US/component/loading.html#service)明确全屏服务为单例，并建议在nextTick异步关闭。最终方案进一步给请求加载层明确的DOM所有权，避免其生命周期依赖全屏单例。

## 修复

- 用户端和管理端各轮并发请求共用自己创建的固定定位容器；Element Plus只覆盖该容器，不借用全屏单例。
- 最后一个请求结束后等待Vue更新，再关闭实例并移除本轮容器，恢复请求前的body滚动样式。
- 每个释放函数幂等，并绑定创建时的那一轮加载记录；旧请求结束不影响新一轮。
- Vite热更新时主动释放本模块拥有的容器和滚动锁。
- 不查询后批量移除其他组件的遮罩；其他任务的全屏加载实例继续正常工作。

## 验证

- 新增`stability-loading.test.mjs`，共10项真实Element Plus组件测试：两端分别覆盖并发完成、五轮四请求循环、网络/401/业务失败、其他全屏任务隔离、热更新与旧请求竞争。
- 全量前端回归93项通过，0失败；日志：`runtime/phase2/step07/frontend-tests-loading-fix.tap`。
- 两端生产构建通过，产物分别写到`runtime/phase2/step07/loading-fix-web/`与`loading-fix-admin/`，使用`--emptyOutDir false`，未删除原产物。
- 浏览器隔离后台重复登录跳转后，实际读取到`remainingMasks:0`、`overflow:""`、`locked:false`，没有控制台错误。截图：`runtime/phase2/step07/browser-admin-loading-fixed.jpg`。
- 验收服务器已停止；用户原后台服务未停止，未读取或修改真实管理员凭据。

复现命令、模拟后台地址及案例17见[前端测试说明](D:/java/java24/LLstudy/mall/easymall-front/tests/README-step07.md)。

用户当前页面需按Ctrl+F5完整刷新一次，以清掉旧模块留下的遮罩并加载新代码。开发模式无需重新启动Java；本轮没有修改Java代码或数据库。
