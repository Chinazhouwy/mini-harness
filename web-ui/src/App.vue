<template>
  <div id="app" class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark">QH</span>
        <div>
          <strong>QuantHarness</strong>
          <small>MVP Console</small>
        </div>
      </div>
      <nav>
        <RouterLink to="/">Dashboard</RouterLink>
        <RouterLink to="/tasks">Tasks</RouterLink>
        <RouterLink to="/market">Market</RouterLink>
      </nav>
    </aside>
    <main class="content">
      <router-view />
    </main>
    <nav class="mobile-nav">
      <RouterLink to="/">首页</RouterLink>
      <RouterLink to="/tasks">任务</RouterLink>
      <RouterLink to="/market">说明</RouterLink>
    </nav>
  </div>
</template>

<script setup lang="ts">
</script>

<style>
* {
  box-sizing: border-box;
}

body {
  margin: 0;
  background:
    linear-gradient(135deg, rgba(21, 88, 99, 0.16), transparent 30%),
    linear-gradient(315deg, rgba(196, 70, 48, 0.12), transparent 28%),
    #f6f7f2;
}

#app {
  font-family: "IBM Plex Sans", "Source Han Sans SC", "Microsoft YaHei", sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #182321;
  min-height: 100vh;
}

.app-shell {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
}

.sidebar {
  min-height: 100vh;
  padding: 28px 20px;
  background: #102321;
  color: #f6f7f2;
}

.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 32px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 8px;
  background: #f0c75e;
  color: #102321;
  font-weight: 800;
}

.brand small {
  display: block;
  color: #a9b8b4;
  margin-top: 3px;
}

nav {
  display: grid;
  gap: 8px;
}

nav a {
  color: #cbd7d3;
  text-decoration: none;
  padding: 11px 12px;
  border-radius: 6px;
}

nav a.router-link-active {
  background: #24433e;
  color: #fff;
}

.mobile-nav {
  display: none;
}

.content {
  min-width: 0;
  padding: 32px;
}

@media (max-width: 820px) {
  .app-shell {
    grid-template-columns: 1fr;
  }

  .sidebar {
    min-height: auto;
    padding: 18px;
  }

  nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .content {
    padding: 18px 18px 76px;
  }

  .mobile-nav {
    position: fixed;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 20;
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 0;
    padding: 8px;
    background: rgba(16, 35, 33, 0.96);
  }

  .mobile-nav a {
    text-align: center;
    border-radius: 6px;
  }
}
</style>
