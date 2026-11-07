# 步骤07补充：管理端退出需要两次

日期：2026-11-07（Asia/Shanghai）。

## 原因与失败证据

管理端`AccountController.logout`原先返回void：第一次请求撤销Redis会话后返回HTTP200空响应。前端Request只接受业务code=200，空体被按失败处理并返回null，Layout中的`if (!result) return`因此跳过清理本地凭据和登录页跳转。

第二次携带旧凭据访问logout时，鉴权返回业务码901，统一登录失效处理才清凭据并跳转。这解释了“第一次HTTP200但未离开页面，第二次才回登录”的现象。HTTP200不等于业务成功体已经符合前端契约。

修复前先加入测试并复现：

- 真实Layout配合实际Request/Axios模拟传输，第一次确认后路由仍为`/home`，期望`/login`的断言失败。
- 后端MockMvc契约测试首个logout响应不含`$.code`，错误为`json can not be null or empty`；当时3项测试中1失败、2通过。
- 后端失败日志保留在`runtime/phase2/step07/logout-backend-before.log`。

## 修复

- 后端logout返回`getSuccessResponseVO(null)`，与其他管理端API统一成功JSON契约。
- 前端仅为`/account/logout`兼容HTTP200/204空体，转换成成功结果。普通接口空体、退出业务失败和HTTP错误仍按原规则拒绝，不笼统把HTTP200当成功。
- Layout在第一次成功后清理adminToken，等待`router.replace('/login')`完成；请求进行中阻止重复退出，失败后允许重试。
- 退出前捕获Token，迟到的旧退出响应不能清除后来的新登录。

旧运行中的管理后端仍可用前端兼容分支完成一次退出；新的后端响应在下次重启管理端后生效。本轮没有替用户停止或重启6002/6061服务，没有读取截图中的真实Token或操作真实登录会话。

## 验证

- 全量前端99项通过：账号/退出8、Layout7、manager5、Worker9、请求18、真实加载组件10、DOM安全42。日志`runtime/phase2/step07/frontend-tests-admin-logout.tap`。
- 后端定向17项通过：`AdminLogoutContractTest`3项、`AdminPasswordProtocolTest`4项、`AppInterceptorSecurityTest`10项；Common/Admin打包成功，日志`runtime/phase2/step07/logout-backend-tests.log`。使用MockMvc、Mockito合成会话，不连接真实Redis/数据库；未重跑全部Java测试。
- 管理端生产构建通过，输出到`runtime/phase2/step07/logout-fix-admin/`，未清空原产物。
- 浏览器分别使用旧版HTTP200空体和新版JSON响应。两种情况下第一次点击退出并确认后均到`/login`，统计均为“退出请求：1，凭据：已清除”，遮罩0、滚动锁false、无控制台错误。
- 截图：`runtime/phase2/step07/browser-admin-logout-legacy.jpg`和`browser-admin-logout-json.jpg`。验收服务与临时标签页均已停止/关闭。

前端回归：

```powershell
cd D:\java\java24\LLstudy\mall\easymall-front
node --test tests/account-protocol.test.mjs tests/stability-*.test.mjs easymall-front-web/tests/*.test.mjs easymall-front-admin/tests/*.test.mjs
```

后端定向验证（在后端根目录，先设置JDK21）：

```powershell
$env:JAVA_HOME = 'C:\Users\CC_Am\.jdks\ms-21.0.11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn.cmd -o -B -ntp '-Dmaven.repo.local=D:\java\java24\wokspach-teach\runtime\phase0\maven-repository' '-Dmaven.test.skip=false' '-DskipTests=false' '-Dtest=AdminLogoutContractTest,AdminPasswordProtocolTest,AppInterceptorSecurityTest' '-Dsurefire.failIfNoSpecifiedTests=false' -pl easymall-admin -am package
```

浏览器复验默认模拟旧后端；增加`--json-logout`模拟新版响应：

```powershell
node tests/browser-admin-loading-server.mjs
# 或
node tests/browser-admin-loading-server.mjs --json-logout
```

打开`http://127.0.0.1:16072/home`，点击后台右上角退出→确定，再点底部“刷新退出统计”，应出现退出请求1次、凭据已清除和登录表单。
