'use strict';

// Everything the model or a tool wrote reaches the page through textContent, never as markup.

const LABELS = {
  recording: 'RECORDING: played back from a file, no model, no gateway',
  scripted: 'SCRIPTED ANSWERS, NOT A MODEL: the real checks, tools and Nkap, with answers written in advance',
  live: 'LIVE: a real model, under a daily budget'
};
const SHORT = { recording: 'RECORDING', scripted: 'SCRIPTED ANSWERS, NOT A MODEL', live: 'LIVE' };

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) { node.className = cls; }
  if (text !== undefined && text !== null) { node.textContent = String(text); }
  return node;
}

function pretty(value) {
  return JSON.stringify(value, null, 2);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function get(path) {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  return { status: response.status, body: await response.json() };
}

async function post(path, payload) {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(payload)
  });
  return { status: response.status, body: await response.json() };
}

const app = document.getElementById('app');
const state = { playing: 0 };

function badge(mode) {
  return el('span', 'badge mode-' + mode, SHORT[mode] || mode);
}

function banner(mode) {
  return el('p', 'banner mode-' + mode, LABELS[mode]);
}

function stepView(step) {
  const data = step.data;
  const box = el('div', 'step step-' + step.type);
  const head = el('h3');
  head.appendChild(badge(step.mode));
  switch (step.type) {
    case 'task':
      head.appendChild(el('span', null, 'The task'));
      box.appendChild(head);
      box.appendChild(el('p', null, data.text));
      break;
    case 'model': {
      head.appendChild(el('span', null, 'The model answers (call ' + data.callIndex + ')'));
      box.appendChild(head);
      if (data.failure) {
        box.appendChild(el('p', 'error', 'The model call failed: ' + data.failure.reason));
      } else {
        const answer = data.answer || {};
        if (answer.action === 'call_tool') {
          box.appendChild(el('p', null, 'Asks for the tool ' + answer.tool + ' with ' + answer.argument));
        } else {
          box.appendChild(el('p', null, 'Gives a final answer: ' + answer.outcome));
          if (answer.explanation) {
            box.appendChild(el('p', 'note', 'Explanation, text written by the model, not checked:'));
            box.appendChild(el('pre', null, answer.explanation));
          }
        }
        box.appendChild(el('pre', null, pretty(answer)));
      }
      break;
    }
    case 'tool':
      head.appendChild(el('span', null, 'Tool result ' + data.callId + ': ' + data.tool + ' (status ' + data.status + ')'));
      box.appendChild(head);
      box.appendChild(el('p', 'note', 'Data returned by a tool, shown as it was given to the model (masked). Untrusted.'));
      box.appendChild(el('pre', null, pretty(data.fields)));
      break;
    case 'harness':
      head.appendChild(el('span', null, 'The harness writes to the model'));
      box.appendChild(head);
      box.appendChild(el('pre', null, data.text));
      break;
    default: {
      box.className = 'step outcome';
      head.appendChild(el('span', null, 'Outcome: ' + data.kind));
      box.appendChild(head);
      const copy = Object.assign({}, data);
      delete copy.type;
      box.appendChild(el('pre', null, pretty(copy)));
    }
  }
  return box;
}

const trace = el('div');
trace.id = 'trace';
const decisionArea = el('div');
const status = el('p', 'small');
let skip = false;

async function show(steps, mode, animate) {
  state.playing += 1;
  const mine = state.playing;
  skip = false;
  trace.replaceChildren(banner(mode));
  for (const step of steps) {
    if (state.playing !== mine) { return; }
    if (animate && !skip && step.delayMillis > 0) { await sleep(step.delayMillis); }
    trace.appendChild(stepView(step));
  }
}

function showError(body, status) {
  const text = body && body.text ? body.text : 'The request was refused (status ' + status + ').';
  trace.replaceChildren(el('p', 'error', text));
  if (body && body.retryAfterSeconds) {
    trace.appendChild(el('p', 'small', 'Try again in about ' + body.retryAfterSeconds + ' seconds.'));
  }
}

function decisionView(result) {
  const box = el('div', 'decision');
  if (result.result === 'decided') {
    box.appendChild(el('p', null, 'Approved by the approver\'s key. Nkap wrote the ledger entry ' + result.ledgerEntryId + '.'));
  } else {
    box.appendChild(el('p', 'refused', 'Nkap refused (' + result.code + '): ' + result.text));
  }
  return box;
}

async function decide(run, kind, buttons) {
  buttons.forEach((b) => { b.disabled = true; });
  const reply = await post('/api/runs/' + run.runId + '/' + kind, {});
  if (reply.status === 200) {
    decisionArea.appendChild(decisionView(reply.body));
    if (reply.body.result === 'decided') { return; }
    // Nkap refused: the run is still open for a few attempts, so the buttons come back.
    if (reply.body.attemptsLeft > 0) {
      decisionArea.appendChild(el('p', 'small', 'This run has ' + reply.body.attemptsLeft + ' more attempt(s).'));
      buttons.forEach((b) => { if (!b.dataset.blocked) { b.disabled = false; } });
    }
    return;
  }
  decisionArea.appendChild(el('p', 'error', reply.body.text || 'The decision was refused.'));
  if (reply.status === 409 && reply.body.error === 'payment-already-approved') {
    buttons.forEach((b) => { if (b.dataset.kind === 'approve') { b.dataset.blocked = 'yes'; } else { b.disabled = false; } });
  }
}

function proposalView(run) {
  decisionArea.replaceChildren();
  const proposal = run.proposal || { status: 'none' };
  if (proposal.status === 'refused') {
    decisionArea.appendChild(el('p', 'refused', 'Nkap refused the proposal (' + proposal.code + '): ' + proposal.text));
  } else if (proposal.status === 'none') {
    decisionArea.appendChild(el('p', null, 'The run proposed nothing, so there is nothing to approve.'));
  }
  if (run.runId) {
    decisionArea.appendChild(el('p', null, 'Nkap recorded the proposal. It is pending, and a different key has to decide it.'));
    const row = el('div', 'row');
    const approve = el('button', null, "Approve with the approver's key");
    const selfApprove = el('button', null, "Try to approve with the agent's own key");
    approve.type = 'button';
    selfApprove.type = 'button';
    approve.dataset.kind = 'approve';
    selfApprove.dataset.kind = 'self-approve';
    const both = [approve, selfApprove];
    approve.addEventListener('click', () => decide(run, 'approve', both));
    selfApprove.addEventListener('click', () => decide(run, 'self-approve', both));
    row.appendChild(approve);
    row.appendChild(selfApprove);
    if (run.approval && run.approval.available === false) {
      approve.disabled = true;
      approve.dataset.blocked = 'yes';
      decisionArea.appendChild(el('p', 'refused', run.approval.text));
    }
    decisionArea.appendChild(row);
    decisionArea.appendChild(el('p', 'small', 'A run takes one decision, and a few refused attempts. The approval is bound to a key, not to a person.'));
  }
}

async function loadRecording(id) {
  decisionArea.replaceChildren();
  const reply = await get('/api/recordings/' + encodeURIComponent(id));
  if (reply.status !== 200) { showError(reply.body, reply.status); return; }
  const p = reply.body.provenance;
  const info = el('p', 'small', 'Recorded ' + p.date + ' with ' + p.model + ': run ' + p.run + ' of ' + p.runs + ' of the case ' + p.case +
    ', ' + (p.matched ? 'matched' : 'did not match') + ' the catalogue. Cost ' + p.costUsd + ' USD.');
  await show(reply.body.steps, 'recording', true);
  trace.appendChild(info);
}

async function runCase(caseId, mode, button) {
  button.disabled = true;
  decisionArea.replaceChildren();
  trace.replaceChildren(banner(mode), el('p', 'small', 'Running...'));
  try {
    const reply = await post('/api/runs', { case: caseId, mode: mode });
    if (reply.status !== 200) { showError(reply.body, reply.status); return; }
    const run = reply.body;
    await show(run.steps, run.mode, true);
    trace.appendChild(el('p', 'small', 'Matched the catalogue: ' + (run.matched ? 'yes' : 'no') + ' (' + run.judgement + ').'));
    proposalView(run);
  } finally {
    button.disabled = false;
    refreshStatus();
  }
}

async function refreshStatus() {
  const reply = await get('/api/status');
  if (reply.status !== 200) { return; }
  status.textContent = reply.body.liveEnabled
    ? 'Live runs left today: ' + reply.body.liveRunsLeftToday + '.'
    : 'Live runs are off on this deployment.';
}

function modeCards() {
  const list = el('ul', 'modes');
  [['recording', 'A file of a past run is played back. Nothing runs.'],
   ['scripted', 'The real loop, checks, tools and Nkap run, with answers written in advance instead of a model. Costs nothing.'],
   ['live', 'The real model runs, if the budget allows. It can get things wrong, which is what the checks are for.']]
    .forEach(([mode, text]) => {
      const item = el('li', 'mode-' + mode);
      item.appendChild(badge(mode));
      item.appendChild(el('p', null, text));
      list.appendChild(item);
    });
  return list;
}

async function start() {
  app.replaceChildren();
  app.appendChild(el('h1', null, 'Nkap Agent'));
  app.appendChild(el('p', 'lede', 'An agent that looks into a mobile-money payment and proposes a ledger adjustment. It proposes with one key; a different key has to approve, or Nkap refuses.'));
  app.appendChild(modeCards());

  app.appendChild(el('h2', null, 'Watch a recording'));
  const recordings = el('div', 'row');
  app.appendChild(recordings);

  app.appendChild(el('h2', null, 'Run it'));
  const controls = el('div', 'row');
  const caseSelect = el('select');
  caseSelect.setAttribute('aria-label', 'Case');
  const modeSelect = el('select');
  modeSelect.setAttribute('aria-label', 'Mode');
  [['scripted', 'Scripted answers'], ['live', 'Live model']].forEach(([value, label]) => {
    const option = el('option', null, label);
    option.value = value;
    modeSelect.appendChild(option);
  });
  const run = el('button', null, 'Run');
  run.type = 'button';
  controls.appendChild(caseSelect);
  controls.appendChild(modeSelect);
  controls.appendChild(run);
  app.appendChild(controls);
  app.appendChild(status);
  app.appendChild(trace);
  app.appendChild(decisionArea);
  run.addEventListener('click', () => runCase(caseSelect.value, modeSelect.value, run));

  const list = await get('/api/recordings');
  const seen = new Set();
  (list.body || []).forEach((r) => {
    const button = el('button', null, r.id + ' (' + r.outcome + (r.matched ? '' : ', did not match') + ')');
    button.type = 'button';
    button.addEventListener('click', () => loadRecording(r.id));
    recordings.appendChild(button);
    if (!seen.has(r.case)) {
      seen.add(r.case);
      const option = el('option', null, r.case);
      option.value = r.case;
      caseSelect.appendChild(option);
    }
  });
  refreshStatus();
}

start();
