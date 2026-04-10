const demoConversations = [
  {
    id: "alice",
    name: "Alice",
    status: "在线",
    messages: [
      { self: false, text: "早上好，今天的接口联调怎么样？", time: "09:10" },
      { self: true, text: "已经差不多了，晚点我把页面再收一版。", time: "09:12" },
      { self: false, text: "好，那我等你消息。", time: "09:13" },
    ],
  },
  {
    id: "design",
    name: "产品设计组",
    status: "3 人在线",
    messages: [
      { self: false, text: "登录页希望更简洁一点。", time: "昨天" },
      { self: true, text: "收到，我会先做正常登录态，再进入聊天界面。", time: "昨天" },
    ],
  },
  {
    id: "ops",
    name: "运维通知",
    status: "系统消息",
    messages: [
      { self: false, text: "测试环境今晚 23:00 短时维护。", time: "周三" },
    ],
  },
];

const state = {
  isLiveMode: false,
  session: null,
  socket: null,
  currentConversationId: demoConversations[0].id,
  conversations: structuredClone(demoConversations),
};

const elements = {
  loginView: document.getElementById("loginView"),
  chatView: document.getElementById("chatView"),
  loginForm: document.getElementById("loginForm"),
  username: document.getElementById("username"),
  password: document.getElementById("password"),
  forgotPasswordButton: document.getElementById("forgotPasswordButton"),
  profileName: document.getElementById("profileName"),
  profileStatus: document.getElementById("profileStatus"),
  contactSearch: document.getElementById("contactSearch"),
  conversationList: document.getElementById("conversationList"),
  conversationItemTemplate: document.getElementById("conversationItemTemplate"),
  chatTitle: document.getElementById("chatTitle"),
  chatSubtitle: document.getElementById("chatSubtitle"),
  activeAvatar: document.getElementById("activeAvatar"),
  messageList: document.getElementById("messageList"),
  composerForm: document.getElementById("composerForm"),
  messageInput: document.getElementById("messageInput"),
  connectionText: document.getElementById("connectionText"),
  composerStatus: document.getElementById("composerStatus"),
  switchModeButton: document.getElementById("switchModeButton"),
  configDialog: document.getElementById("configDialog"),
  connectLiveButton: document.getElementById("connectLiveButton"),
  configForm: document.getElementById("configForm"),
  apiBase: document.getElementById("apiBase"),
  appId: document.getElementById("appId"),
  clientType: document.getElementById("clientType"),
  imei: document.getElementById("imei"),
  wsUrl: document.getElementById("wsUrl"),
  newChatButton: document.getElementById("newChatButton"),
};

bindEvents();
renderConversations();
renderActiveConversation();

function bindEvents() {
  elements.loginForm.addEventListener("submit", handleFakeLogin);
  elements.forgotPasswordButton.addEventListener("click", () => {
    window.alert("默认密码就是 123456，演示页面先不用真的找回。");
  });
  elements.contactSearch.addEventListener("input", renderConversations);
  elements.composerForm.addEventListener("submit", handleSendMessage);
  elements.messageInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      elements.composerForm.requestSubmit();
    }
  });
  elements.switchModeButton.addEventListener("click", () => {
    if (typeof elements.configDialog.showModal === "function") {
      elements.configDialog.showModal();
    }
  });
  elements.connectLiveButton.addEventListener("click", handleLiveConnect);
  elements.newChatButton.addEventListener("click", createManualConversation);
}

function handleFakeLogin(event) {
  event.preventDefault();

  const username = elements.username.value.trim();
  const password = elements.password.value.trim();

  if (username !== "user" || password !== "123456") {
    window.alert("请使用默认账号 user 和密码 123456。");
    return;
  }

  elements.profileName.textContent = username;
  elements.profileStatus.textContent = "演示模式已登录";
  elements.loginView.classList.add("is-hidden");
  elements.chatView.classList.remove("is-hidden");
}

function renderConversations() {
  const keyword = elements.contactSearch.value.trim().toLowerCase();
  const list = state.conversations.filter((item) => {
    if (!keyword) {
      return true;
    }
    return `${item.name} ${item.id}`.toLowerCase().includes(keyword);
  });

  elements.conversationList.innerHTML = "";

  list.forEach((item) => {
    const fragment = elements.conversationItemTemplate.content.cloneNode(true);
    const button = fragment.querySelector(".conversation-item");
    const preview = item.messages[item.messages.length - 1]?.text || "暂无消息";
    const time = item.messages[item.messages.length - 1]?.time || "";

    button.classList.toggle("is-active", item.id === state.currentConversationId);
    fragment.querySelector(".conversation-item__avatar").textContent = item.name.slice(0, 1).toUpperCase();
    fragment.querySelector(".conversation-item__name").textContent = item.name;
    fragment.querySelector(".conversation-item__preview").textContent = preview;
    fragment.querySelector(".conversation-item__time").textContent = time;
    button.addEventListener("click", () => {
      state.currentConversationId = item.id;
      renderConversations();
      renderActiveConversation();
    });

    elements.conversationList.appendChild(fragment);
  });
}

function renderActiveConversation() {
  const active = getCurrentConversation();
  if (!active) {
    return;
  }

  elements.chatTitle.textContent = active.name;
  elements.chatSubtitle.textContent = state.isLiveMode ? `${active.status} · 真实联调` : active.status;
  elements.activeAvatar.textContent = active.name.slice(0, 1).toUpperCase();
  elements.messageList.innerHTML = "";

  active.messages.forEach((message) => {
    const item = document.createElement("article");
    item.className = `message-group ${message.self ? "message-group--self" : "message-group--peer"}`;
    item.innerHTML = `
      <div class="message-bubble">${escapeHtml(message.text)}</div>
      <div class="message-meta">${message.self ? "我" : active.name} · ${message.time}</div>
    `;
    elements.messageList.appendChild(item);
  });

  elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

function handleSendMessage(event) {
  event.preventDefault();
  const text = elements.messageInput.value.trim();
  if (!text) {
    return;
  }

  const active = getCurrentConversation();
  if (!active) {
    return;
  }

  if (state.isLiveMode && state.socket?.readyState === WebSocket.OPEN && state.session) {
    const payload = {
      messageId: crypto.randomUUID(),
      fromId: state.session.userId,
      toId: active.id,
      appId: state.session.appId,
      clientType: state.session.clientType,
      imei: state.session.imei,
      messageBody: text,
    };
    sendPack(1103, JSON.stringify(payload));
  }

  active.messages.push({
    self: true,
    text,
    time: formatNow(),
  });

  elements.messageInput.value = "";
  renderConversations();
  renderActiveConversation();
}

async function handleLiveConnect() {
  const userId = elements.username.value.trim() || "user";
  const apiBase = normalizeBaseUrl(elements.apiBase.value);
  const appId = Number(elements.appId.value);
  const clientType = Number(elements.clientType.value);
  const imei = elements.imei.value.trim();

  try {
    const loginResp = await postJson(`${apiBase}/v1/user/login?appId=${appId}`, {
      userId,
      appId,
      clientType,
    });
    ensureOk(loginResp, "登录失败");

    const userSigResp = await postJson(`${apiBase}/v1/user/genUserSig`, {
      userId,
      appId,
      expireSeconds: 60 * 60 * 24 * 7,
    });
    ensureOk(userSigResp, "生成 UserSig 失败");

    state.session = {
      userId,
      apiBase,
      appId,
      clientType,
      imei,
      userSign: userSigResp.data,
      wsUrl: resolveWsUrl(elements.wsUrl.value.trim(), loginResp.data),
    };

    await connectSocket();
    state.isLiveMode = true;
    elements.profileStatus.textContent = "真实联调已连接";
    elements.connectionText.textContent = "真实服务已连接";
    elements.composerStatus.textContent = "真实联调模式";
    elements.configDialog.close();
  } catch (error) {
    console.error(error);
    window.alert(error.message || "连接失败");
  }
}

async function connectSocket() {
  if (state.socket) {
    state.socket.close();
  }

  const socket = new WebSocket(state.session.wsUrl);
  socket.binaryType = "arraybuffer";
  state.socket = socket;

  await new Promise((resolve, reject) => {
    socket.onopen = () => {
      sendPack(9000, JSON.stringify({
        userId: state.session.userId,
        appId: state.session.appId,
        clientType: state.session.clientType,
        imei: state.session.imei,
        customStatus: null,
        customClientName: "web-chat",
      }));
      resolve();
    };
    socket.onerror = () => reject(new Error("WebSocket 连接失败"));
  });

  socket.onmessage = (event) => {
    const byteBuffer = new ByteBuffer(event.data);
    const [command, bodyLength] = byteBuffer.int32().int32().unpack();
    const bodyText = byteBuffer.vstring(null, bodyLength).unpack()[2];

    if (command !== 1103) {
      return;
    }

    const payload = JSON.parse(bodyText);
    const data = payload.data || payload;
    let conversation = state.conversations.find((item) => item.id === data.fromId);
    if (!conversation) {
      conversation = {
        id: data.fromId,
        name: data.fromId,
        status: "在线",
        messages: [],
      };
      state.conversations.unshift(conversation);
    }

    conversation.messages.push({
      self: false,
      text: data.messageBody,
      time: formatNow(),
    });

    state.currentConversationId = conversation.id;
    renderConversations();
    renderActiveConversation();
  };
}

function createManualConversation() {
  const id = window.prompt("输入对方用户名");
  if (!id) {
    return;
  }

  let conversation = state.conversations.find((item) => item.id === id);
  if (!conversation) {
    conversation = {
      id,
      name: id,
      status: "新会话",
      messages: [],
    };
    state.conversations.unshift(conversation);
  }

  state.currentConversationId = id;
  renderConversations();
  renderActiveConversation();
}

function getCurrentConversation() {
  return state.conversations.find((item) => item.id === state.currentConversationId);
}

function postJson(url, body) {
  return fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }).then((response) => response.json());
}

function ensureOk(response, message) {
  if (!response || response.code !== 200) {
    throw new Error(response?.msg || message);
  }
}

function resolveWsUrl(manualUrl, routeInfo) {
  if (manualUrl) {
    return manualUrl;
  }
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  return `${protocol}://${routeInfo.ip}:${routeInfo.port}/ws`;
}

function normalizeBaseUrl(value) {
  return value.trim().replace(/\/$/, "");
}

function formatNow() {
  return new Date().toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function sendPack(command, bodyText) {
  const { appId, clientType, imei } = state.session;
  const packet = new ByteBuffer();
  packet.int32(command)
    .int32(1)
    .int32(clientType)
    .int32(0)
    .int32(appId)
    .int32(getUtf8Length(imei))
    .int32(getUtf8Length(bodyText))
    .vstring(imei, getUtf8Length(imei))
    .vstring(bodyText, getUtf8Length(bodyText));
  state.socket.send(packet.pack());
}

function getUtf8Length(str) {
  return new TextEncoder().encode(str).length;
}

function ByteBuffer(arrayBuf, offset = 0) {
  this.offset = offset;
  this.list = [];
  this.buffer = arrayBuf
    ? (arrayBuf.constructor === DataView
      ? arrayBuf
      : new DataView(arrayBuf instanceof Uint8Array ? arrayBuf.buffer : arrayBuf, offset))
    : new DataView(new Uint8Array([]).buffer);
}

ByteBuffer.prototype.int32 = function (value) {
  if (arguments.length === 0) {
    this.list.push(this.buffer.getInt32(this.offset, false));
    this.offset += 4;
  } else {
    this.list.push({ type: "int32", value, length: 4 });
    this.offset += 4;
  }
  return this;
};

ByteBuffer.prototype.vstring = function (value, length) {
  if (value == null) {
    const bytes = new Uint8Array(this.buffer.buffer.slice(this.offset, this.offset + length));
    this.list.push(new TextDecoder().decode(bytes));
    this.offset += length;
  } else {
    this.list.push({ type: "vstring", value, length });
    this.offset += length;
  }
  return this;
};

ByteBuffer.prototype.unpack = function () {
  return this.list;
};

ByteBuffer.prototype.pack = function () {
  const view = new DataView(new ArrayBuffer(this.offset));
  let cursor = 0;
  this.list.forEach((item) => {
    if (item.type === "int32") {
      view.setInt32(cursor, item.value, false);
      cursor += item.length;
      return;
    }

    const bytes = new TextEncoder().encode(item.value);
    for (let i = 0; i < item.length; i += 1) {
      view.setUint8(cursor + i, bytes[i] ?? 0);
    }
    cursor += item.length;
  });
  return view.buffer;
};
