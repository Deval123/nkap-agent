'use strict';

// Everything the model or a tool wrote reaches the page through textContent, never as markup.

const LABELS = {
  recording: 'RECORDING: played back from a file, no model, no gateway',
  scripted: 'SCRIPTED ANSWERS, NOT A MODEL: the real checks, tools and Nkap, with answers written in advance',
  live: 'LIVE: a real model, under a daily budget'
};
const SHORT = { recording: 'RECORDING', scripted: 'SCRIPTED ANSWERS, NOT A MODEL', live: 'LIVE' };
const MODE_HELP = {
  scripted: 'The real loop, checks, tools and Nkap run, with answers written in advance instead of a model. Costs nothing.',
  live: 'The real model runs, if the budget allows. It can get things wrong, which is what the checks are for.'
};
const WORKING = {
  scripted: 'Running the real checks.',
  live: 'The model is working. A live run takes about 15 seconds.'
};

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

const state = { playing: 0 };
const slip = document.getElementById('slip');
const slipTitle = document.getElementById('slip-title');
const working = document.getElementById('working');
const trace = document.getElementById('trace');
const signatures = document.getElementById('signatures');
const decisionArea = document.getElementById('decision');
const statusLine = document.getElementById('status');
let skip = false;

working.setAttribute('role', 'status');

function reduceMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function reveal(node, block) {
  node.scrollIntoView({ behavior: reduceMotion() ? 'auto' : 'smooth', block: block || 'start' });
}

function endOfRun() {
  slipTitle.focus({ preventScroll: true });
}

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
function showError(body, httpStatus) {
  const text = body && body.text ? body.text : 'The request was refused (status ' + httpStatus + '). Wait a moment, then press Run again.';
  trace.replaceChildren(el('p', 'error', text));
  if (body && body.retryAfterSeconds) {
    trace.appendChild(el('p', 'small', 'Try again in about ' + body.retryAfterSeconds + ' seconds.'));
  }
}

// The two signature lines at the foot of the slip. The first is filled once Nkap recorded a proposal; the second stays
// empty until the approver's key decides, or carries a stamp when Nkap refuses.
function showSignatures() {
  const proposed = el('div', 'sign sign-filled');
  proposed.appendChild(el('p', 'sign-name', "Proposed with the agent's key"));
  proposed.appendChild(el('p', 'sign-state', 'Nkap recorded the proposal. It is pending.'));
  const approved = el('div', 'sign sign-empty');
  approved.id = 'approver-line';
  approved.appendChild(el('p', 'sign-name', 'Approved with a different key'));
  approved.appendChild(el('p', 'sign-state', 'Empty until the approver\'s key decides.'));
  signatures.replaceChildren(proposed, approved);
  signatures.hidden = false;
}

function approverLine() {
  return document.getElementById('approver-line');
}

function clearMarks(line) {
  line.querySelectorAll('.stamp, .seal').forEach((node) => node.remove());
}

function sealApproverLine(ledgerEntryId) {
  const line = approverLine();
  if (!line) { return; }
  clearMarks(line);
  line.className = 'sign sign-sealed';
  const seal = el('div', 'seal');
  seal.appendChild(el('span', 'seal-word', 'Approved'));
  line.appendChild(seal);
  line.querySelector('.sign-state').textContent = 'Ledger entry ' + ledgerEntryId;
}

function stampApproverLine(code) {
  const line = approverLine();
  if (!line) { return; }
  clearMarks(line);
  line.className = 'sign sign-empty sign-refused';
  line.querySelector('.sign-state').textContent = 'Not signed. Nkap refused.';
  line.appendChild(el('div', 'stamp', code));
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
    if (reply.body.result === 'decided') {
      sealApproverLine(reply.body.ledgerEntryId);
      reveal(signatures, 'nearest');
      return;
    }
    // Nkap refused: the run is still open for a few attempts, so the buttons come back.
    stampApproverLine(reply.body.code);
    reveal(signatures, 'nearest');
    if (reply.body.attemptsLeft > 0) {
      decisionArea.appendChild(el('p', 'small', 'This run has ' + reply.body.attemptsLeft + ' more attempt(s).'));
      buttons.forEach((b) => { if (!b.dataset.blocked) { b.disabled = false; } });
    }
    return;
  }
  decisionArea.appendChild(el('p', 'error', reply.body.text || 'The decision was refused. Press the button again, or run the case again.'));
  if ((reply.status === 409 || reply.status === 403) && reply.body.error) {
    stampApproverLine(reply.body.error);
  }
  if (reply.status === 409 && reply.body.error === 'payment-already-approved') {
    buttons.forEach((b) => { if (b.dataset.kind === 'approve') { b.dataset.blocked = 'yes'; } else { b.disabled = false; } });
  }
  reveal(signatures, 'nearest');
}

function proposalView(run) {
  decisionArea.replaceChildren();
  signatures.replaceChildren();
  signatures.hidden = true;
  const proposal = run.proposal || { status: 'none' };
  if (proposal.status === 'refused') {
    decisionArea.appendChild(el('p', 'refused', 'Nkap refused the proposal (' + proposal.code + '): ' + proposal.text));
  } else if (proposal.status === 'none') {
    decisionArea.appendChild(el('p', null, 'The run proposed nothing, so there is nothing to approve.'));
  }
  if (run.runId) {
    showSignatures();
    decisionArea.appendChild(el('p', null, 'Nkap recorded the proposal. It is pending, and a different key has to decide it.'));
    const row = el('div', 'row');
    const approve = el('button', 'approve', "Approve with the approver's key");
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

function clearSlip() {
  decisionArea.replaceChildren();
  signatures.replaceChildren();
  signatures.hidden = true;
}

async function loadRecording(id) {
  clearSlip();
  reveal(slip);
  const reply = await get('/api/recordings/' + encodeURIComponent(id));
  if (reply.status !== 200) { showError(reply.body, reply.status); endOfRun(); return; }
  const p = reply.body.provenance;
  const info = el('p', 'small', 'Recorded ' + p.date + ' with ' + p.model + ': run ' + p.run + ' of ' + p.runs + ' of the case ' + p.case +
    ', ' + (p.matched ? 'matched' : 'did not match') + ' the catalogue. Cost ' + p.costUsd + ' USD.');
  await show(reply.body.steps, 'recording', true);
  trace.appendChild(info);
  endOfRun();
}

function workingView(mode) {
  const elapsed = el('p', 'elapsed', 'Elapsed: 0 s');
  elapsed.setAttribute('aria-hidden', 'true');
  const bar = el('div', 'bar');
  bar.setAttribute('aria-hidden', 'true');
  bar.appendChild(el('span'));
  working.replaceChildren(el('p', 'working-text', WORKING[mode] || WORKING.scripted), elapsed, bar);
  return elapsed;
}

async function runCase(caseId, mode, button) {
  button.disabled = true;
  button.textContent = 'Running';
  clearSlip();
  trace.replaceChildren(banner(mode));
  const elapsed = workingView(mode);
  reveal(slip);
  let seconds = 0;
  const counter = setInterval(() => {
    seconds += 1;
    elapsed.textContent = 'Elapsed: ' + seconds + ' s';
  }, 1000);
  try {
    let reply = null;
    try {
      reply = await post('/api/runs', { case: caseId, mode: mode });
    } catch (failure) {
      reply = null;
    } finally {
      clearInterval(counter);
      working.replaceChildren();
    }
    if (reply === null) {
      trace.replaceChildren(el('p', 'error', 'The request did not reach the service, or its answer could not be read. Check your connection, then press Run again.'));
      return;
    }
    if (reply.status !== 200) { showError(reply.body, reply.status); return; }
    const run = reply.body;
    await show(run.steps, run.mode, true);
    trace.appendChild(el('p', 'small', 'Matched the catalogue: ' + (run.matched ? 'yes' : 'no') + ' (' + run.judgement + ').'));
    proposalView(run);
    if (run.runId) { reveal(signatures); }
  } finally {
    button.disabled = false;
    button.textContent = 'Run';
    endOfRun();
    refreshStatus();
  }
}

async function refreshStatus() {
  const reply = await get('/api/status');
  if (reply.status !== 200) { return; }
  statusLine.textContent = reply.body.liveEnabled
    ? 'Live runs left today: ' + reply.body.liveRunsLeftToday + '.'
    : 'Live runs are off on this deployment.';
}

async function start() {
  const caseSelect = document.getElementById('case');
  const run = document.getElementById('run');
  const recordings = document.getElementById('recordings');
  const help = document.getElementById('mode-help');
  const modes = document.querySelectorAll('input[name="mode"]');
  const chosen = () => Array.from(modes).find((m) => m.checked).value;
  const explain = () => { help.textContent = MODE_HELP[chosen()]; };
  modes.forEach((m) => m.addEventListener('change', explain));
  explain();

  trace.appendChild(el('p', 'empty', 'Nothing has run yet. Choose a case and press Run, or open a past run.'));
  run.addEventListener('click', () => runCase(caseSelect.value, chosen(), run));

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
