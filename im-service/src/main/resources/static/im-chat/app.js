const state = {
  session: null,
  socket: null,
  currentPeerId: "",
  friends: [],
  messages: loadMessages(),
};

const elements = {
  loginForm: document.getElementById("loginForm"),
  apiBase: document.getElementById("apiBase"),
  userId: document.getElementById("userId"),
  appId: document.getElementById("appId"),
  clientType: document.getElementById("clientType"),
  imei: document.getElementById("imei"),
  wsUrl: document.getElementById("wsUrl"),
  refreshFriendsButton: document.getElementById("refreshFriendsButton"),
  manualPeerId: document.getElementById("manualPeerId"),
  startChatButton: document.getElementById("startChatButton"),
  friendList: document.getElementById("friendList"),
  friendItemTemplate: document.getElementById("friendItemTemplate"),
  connectionBadge: document.getElementById("connectionBadge"),
  connectionText: document.getElementById("connectionText"),
  chatTitle: document.getElementById("chatTitle"),
  currentPeerMeta: document.getElementById("currentPeerMeta"),
  messageList: document.getElementById("messageList"),
  composerForm: document.getElementById("composerForm"),
  messageInput: document.getElementById("messageInput"),
  composerHint: document.getElementById("composerHint"),
};

bindEvents();
renderFriends();
renderMessages();

function bindEvents() {
  elements.loginForm.addEventListener("submit", handleLogin);
  elements.refreshFriendsButton.addEventListener("click", () => {
    if (state.session) {
      refreshFriends();
    }
  });
  elements.startChatButton.addEventListener("click", () => {
    const peerId = elements.manualPeerId.value.trim();
    if (!peerId) {
      updateStatus("请输入对方 userId。", "error");
      return;
    }
    openConversation(peerId);
  });
  elements.composerForm.addEventListener("submit", handleSendMessage);
  elements.messageInput.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      elements.composerForm.requestSubmit();
    }
  });
}

async function handleLogin(event) {
  event.preventDefault();

  const apiBase = normalizeBaseUrl(elements.apiBase.value);
  const userId = elements.userId.value.trim();
  const appId = Number(elements.appId.value);
  const clientType = Number(elements.clientType.value);
  const imei = elements.imei.value.trim();

  if (!apiBase || !userId || !appId || !clientType || !imei) {
    updateStatus("请完整填写登录参数。", "error");
    return;
  }

  updateStatus("正在登录并建立连接...", "idle");

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

    const wsUrl = resolveWsUrl(elements.wsUrl.value.trim(), loginResp.data);
    state.session = {
      apiBase,
      userId,
      appId,
      clientType,
      imei,
      userSign: userSigResp.data,
      wsUrl,
    };

    persistSession(state.session);
    await connectSocket();
    await refreshFriends();
  } catch (error) {
    console.error(error);
    updateStatus(error.message || "登录失败，请检查服务和配置。", "error");
  }
}

async function refreshFriends() {
  if (!state.session) {
    return;
  }

  updateStatus("正在同步好友列表...", "idle");

  const { apiBase, appId, userId, userSign } = state.session;
  const query = new URLSearchParams({
    appId: String(appId),
    identifier: userId,
    userSign,
  });

  try {
    const response = await postJson(`${apiBase}/v1/friendship/getAllFriendShip?${query.toString()}`, {
      fromId: userId,
      appId,
      operater: userId,
      clientType: state.session.clientType,
      imei: state.session.imei,
    });
    ensureOk(response, "拉取好友列表失败");

    state.friends = Array.isArray(response.data) ? response.data.filter(Boolean) : [];
    renderFriends();
    if (!state.currentPeerId && state.friends.length) {
      openConversation(state.friends[0].toId);
    }
    updateStatus(`已连接，好友数 ${state.friends.length}。`, "online");
  } catch (error) {
    console.error(error);
    updateStatus(error.message || "好友列表加载失败。", "error");
  }
}

async function connectSocket() {
  if (!state.session) {
    return;
  }

  if (state.socket) {
    state.socket.close();
  }

  const socket = new WebSocket(state.session.wsUrl);
  socket.binaryType = "arraybuffer";
  state.socket = socket;

  await new Promise((resolve, reject) => {
    socket.onopen = () => {
      sendLoginPack();
      updateStatus(`WebSocket 已连接：${state.session.wsUrl}`, "online");
      resolve();
    };

    socket.onerror = () => {
      reject(new Error("WebSocket 连接失败，请确认 im-tcp 已启动且 WebSocket 端口可用。"));
    };
  });

  socket.onmessage = (event) => handleSocketMessage(event.data);
  socket.onclose = () => {
    updateStatus("连接已关闭，可以重新登录恢复。", "error");
  };
}

function sendLoginPack() {
  const { userId, appId, clientType, imei } = state.session;
  const body = JSON.stringify({
    userId,
    appId,
    clientType,
    imei,
    customStatus: null,
    customClientName: "web-chat",
  });

  sendPack(9000, body);
}

function handleSendMessage(event) {
  event.preventDefault();

  if (!state.session || !state.socket || state.socket.readyState !== WebSocket.OPEN) {
    updateStatus("当前还没有可用连接。", "error");
    return;
  }

  if (!state.currentPeerId) {
    updateStatus("请先选择一个聊天对象。", "error");
    return;
  }

  const text = elements.messageInput.value.trim();
  if (!text) {
    return;
  }

  const payload = {
    messageId: crypto.randomUUID(),
    fromId: state.session.userId,
    toId: state.currentPeerId,
    appId: state.session.appId,
    clientType: state.session.clientType,
    imei: state.session.imei,
    messageBody: text,
  };

  sendPack(1103, JSON.stringify(payload));
  pushMessage(state.currentPeerId, {
    id: payload.messageId,
    self: true,
    text,
    fromId: payload.fromId,
    toId: payload.toId,
    createdAt: new Date().toISOString(),
    status: "sent",
  });
  elements.messageInput.value = "";
  renderMessages();
}

function handleSocketMessage(buffer) {
  const byteBuffer = new ByteBuffer(buffer);
  const [command, bodyLength] = byteBuffer.int32().int32().unpack();
  const packet = byteBuffer.vstring(null, bodyLength).unpack();
  const bodyText = packet[2];

  if (command === 1103) {
    const payload = JSON.parse(bodyText);
    const data = payload.data || payload;
    const peerId = data.fromId === state.session.userId ? data.toId : data.fromId;

    if (data.fromId !== state.session.userId) {
      sendMessageReceivedAck(data);
      sendMessageReadAck(data);
    }

    pushMessage(peerId, {
      id: data.messageId || crypto.randomUUID(),
      self: data.fromId === state.session.userId,
      text: data.messageBody,
      fromId: data.fromId,
      toId: data.toId,
      createdAt: new Date().toISOString(),
      status: "received",
    });

    if (!state.currentPeerId) {
      openConversation(peerId);
    } else {
      renderFriends();
      renderMessages();
    }
  }
}

function sendMessageReceivedAck(data) {
  const body = JSON.stringify({
    fromId: state.session.userId,
    toId: data.fromId,
    messageKey: data.messageKey,
    messageId: data.messageId,
    messageSequence: data.messageSequence,
  });
  sendPack(1107, body);
}

function sendMessageReadAck(data) {
  const body = JSON.stringify({
    fromId: state.session.userId,
    toId: data.fromId,
    conversationType: 0,
    messageSequence: data.messageSequence,
  });
  sendPack(1106, body);
}

function sendPack(command, bodyText) {
  const { appId, clientType, imei } = state.session;
  const imeiLength = getUtf8Length(imei);
  const bodyLength = getUtf8Length(bodyText);
  const version = 1;
  const messageType = 0;

  const packet = new ByteBuffer();
  packet.int32(command)
    .int32(version)
    .int32(clientType)
    .int32(messageType)
    .int32(appId)
    .int32(imeiLength)
    .int32(bodyLength)
    .vstring(imei, imeiLength)
    .vstring(bodyText, bodyLength);

  state.socket.send(packet.pack());
}

function openConversation(peerId) {
  state.currentPeerId = peerId;
  elements.chatTitle.textContent = peerId;
  elements.currentPeerMeta.textContent = `当前会话对象：${peerId}`;
  elements.manualPeerId.value = peerId;
  renderFriends();
  renderMessages();
}

function renderFriends() {
  elements.friendList.innerHTML = "";

  if (!state.friends.length) {
    elements.friendList.innerHTML = '<div class="empty-state">还没有加载到好友列表，你也可以直接在上方输入对方 userId 开始聊天。</div>';
    return;
  }

  state.friends.forEach((friend) => {
    const fragment = elements.friendItemTemplate.content.cloneNode(true);
    const button = fragment.querySelector(".friend-item");
    const peerId = friend.toId;
    const remark = friend.remark || "未设置备注";
    button.classList.toggle("is-active", peerId === state.currentPeerId);
    fragment.querySelector(".friend-item__avatar").textContent = peerId.slice(0, 1).toUpperCase();
    fragment.querySelector(".friend-item__name").textContent = remark;
    fragment.querySelector(".friend-item__meta").textContent = `${peerId} · ${describeFriend(friend)}`;
    button.addEventListener("click", () => openConversation(peerId));
    elements.friendList.appendChild(fragment);
  });
}

function renderMessages() {
  elements.messageList.innerHTML = "";
  const list = state.currentPeerId ? state.messages[state.currentPeerId] || [] : [];

  if (!list.length) {
    elements.messageList.innerHTML = '<div class="empty-state">这个会话还没有消息，发一条试试看。</div>';
    elements.composerHint.textContent = state.currentPeerId ? `准备发送给 ${state.currentPeerId}` : "当前未选择会话";
    return;
  }

  list.forEach((message) => {
    const item = document.createElement("article");
    item.className = `message ${message.self ? "message--self" : "message--peer"}`;
    item.innerHTML = `
      <div class="message__bubble">${escapeHtml(message.text)}</div>
      <div class="message__meta">${message.self ? "我" : message.fromId} · ${formatTime(message.createdAt)}</div>
    `;
    elements.messageList.appendChild(item);
  });

  elements.composerHint.textContent = state.currentPeerId ? `准备发送给 ${state.currentPeerId}` : "当前未选择会话";
  elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

function pushMessage(peerId, message) {
  if (!state.messages[peerId]) {
    state.messages[peerId] = [];
  }
  state.messages[peerId].push(message);
  saveMessages();
}

function updateStatus(text, kind) {
  elements.connectionText.textContent = text;
  elements.connectionBadge.textContent = kind === "online" ? "已连接" : kind === "error" ? "异常" : "处理中";
  elements.connectionBadge.className = `badge ${kind === "online" ? "badge--online" : kind === "error" ? "badge--error" : "badge--idle"}`;
}

function describeFriend(friend) {
  const parts = [];
  if (friend.status != null) {
    parts.push(`状态 ${friend.status}`);
  }
  if (friend.black != null) {
    parts.push(friend.black === 1 ? "正常" : "已拉黑");
  }
  return parts.join(" · ") || "好友";
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

async function postJson(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
  return response.json();
}

function ensureOk(response, fallbackMessage) {
  if (!response || response.code !== 200) {
    throw new Error(response?.msg || fallbackMessage);
  }
}

function loadMessages() {
  try {
    return JSON.parse(localStorage.getItem("im-chat-messages") || "{}");
  } catch (error) {
    console.error(error);
    return {};
  }
}

function saveMessages() {
  localStorage.setItem("im-chat-messages", JSON.stringify(state.messages));
}

function persistSession(session) {
  localStorage.setItem("im-chat-session", JSON.stringify(session));
}

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : date.toLocaleString("zh-CN", { hour12: false });
}

function escapeHtml(text) {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
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
    } else if (item.type === "vstring") {
      const bytes = new TextEncoder().encode(item.value);
      for (let i = 0; i < item.length; i += 1) {
        view.setUint8(cursor + i, bytes[i] ?? 0);
      }
      cursor += item.length;
    }
  });
  return view.buffer;
};
