document.addEventListener('DOMContentLoaded', () => {
    // Navigation Logic
    const navBtns = document.querySelectorAll('.nav-btn');
    const views = document.querySelectorAll('.view');

    navBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            // Remove active classes
            navBtns.forEach(b => b.classList.remove('active'));
            views.forEach(v => v.classList.remove('active'));

            // Add active to clicked
            btn.classList.add('active');
            const targetId = btn.getAttribute('data-target');
            document.getElementById(targetId).classList.add('active');
        });
    });

    // Marked.js config
    marked.setOptions({
        gfm: true,
        breaks: true
    });

    const safeRender = (md) => DOMPurify.sanitize(marked.parse(md));

    // --- 1. Draft Content ---
    const draftForm = document.getElementById('draft-form');
    const draftResults = document.getElementById('draft-results');

    draftForm.addEventListener('submit', async (e) => {
        e.preventDefault();
        const topic = document.getElementById('topic-input').value;
        const spinner = draftForm.querySelector('.spinner');

        spinner.classList.remove('hidden');
        draftResults.classList.add('hidden');

        try {
            const res = await fetch('/api/draft', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ topic })
            });
            if (!res.ok) throw new Error(`HTTP ${res.status}`);
            const data = await res.json();

            document.getElementById('draft-content').innerHTML = safeRender(data.draft);
            document.getElementById('refined-content').innerHTML = safeRender(data.refined);

            draftResults.classList.remove('hidden');
        } catch (err) {
            console.error(err);
            alert('Error generating draft. Check console.');
        } finally {
            spinner.classList.add('hidden');
        }
    });

    // --- 2. Parallel Summarization ---
    const summarizeForm = document.getElementById('summarize-form');
    const summarizeResults = document.getElementById('summarize-results');

    let summarizeEventSource = null;

    summarizeForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const articleUrl = document.getElementById('article-url').value;
        const spinner = summarizeForm.querySelector('.spinner');

        spinner.classList.remove('hidden');
        summarizeResults.classList.remove('hidden');

        const smallContainer = document.getElementById('small-summaries');
        const comparisonEl = document.getElementById('final-comparison');
        smallContainer.innerHTML = '';
        comparisonEl.innerHTML = '';
        
        let smallSummaryCount = 0;

        if (summarizeEventSource) {
            summarizeEventSource.close();
        }

        summarizeEventSource = new EventSource(`/api/summarize/stream?url=${encodeURIComponent(articleUrl)}`);

        summarizeEventSource.onmessage = (e) => {
            const data = JSON.parse(e.data);

            if (data.agentName.startsWith('summarize_') || data.agentName === 'SummarizerAgent') {
                // If it's a batch it might return an array, but we are listening to all agents so we might get the individual SummarizerAgents.
                // Wait, AgentListener triggers for SummarizerAgent if inheritedBySubagents() is true!
                if (typeof data.output === 'string') {
                    smallSummaryCount++;
                    smallContainer.innerHTML += `
                        <div class="summary-card glass-panel fade-in-up">
                            <div class="badge purple-badge" style="margin-bottom: 12px; display: inline-block;">Model Output ${smallSummaryCount}</div>
                            <div class="markdown-content">${safeRender(data.output)}</div>
                        </div>
                    `;
                }
            } else if (data.agentName === 'compareSummaries') {
                comparisonEl.innerHTML += `
                    <div style="margin-bottom: 32px" class="fade-in-up">
                        <span class="badge green-badge">Small Model Comparison</span>
                        <h3 style="margin: 12px 0;">Preferred Summary: #${data.output.index + 1}</h3>
                        <div class="markdown-content">${safeRender(data.output.rationale)}</div>
                    </div>
                `;
            } else if (data.agentName === 'summarizeBig') {
                comparisonEl.innerHTML += `
                    <div style="margin-bottom: 32px; padding-top: 24px; border-top: 1px solid rgba(255,255,255,0.1)" class="fade-in-up">
                        <span class="badge red-badge">Big Model Output</span>
                        <div class="markdown-content" style="margin-top:12px;">${safeRender(data.output)}</div>
                    </div>
                `;
            } else if (data.agentName === 'compareFinal') {
                comparisonEl.innerHTML += `
                    <div class="winner-block fade-in-up">
                        <span class="badge purple-badge" style="font-size:14px; padding: 6px 12px;">Ultimate Winner: ${data.output.winner}</span>
                        <div class="markdown-content" style="margin-top:12px;">${safeRender(data.output.rationale)}</div>
                    </div>
                `;
                spinner.classList.add('hidden');
                summarizeEventSource.close();
            }
        };

        summarizeEventSource.onerror = (e) => {
            console.error('Error running evaluation', e);
            alert('Error running evaluation. Check console.');
            spinner.classList.add('hidden');
            summarizeEventSource.close();
        };
    });

    // --- 3. Content Specialist (SSE) ---
    const specialistForm = document.getElementById('specialist-form');
    const specialistWorkspace = document.getElementById('specialist-workspace');
    const streamLogs = document.getElementById('stream-logs');
    const infoContainer = document.getElementById('infographic-container');
    const infoImg = document.getElementById('infographic-img');
    const textContainer = document.getElementById('text-result-container');
    const textContent = document.getElementById('text-result-content');
    let eventSource = null;

    specialistForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const url = document.getElementById('specialist-url').value;
        const goal = document.getElementById('specialist-goal').value;
        const pulse = specialistForm.querySelector('.pulse-indicator');

        pulse.classList.remove('hidden');
        specialistWorkspace.classList.remove('hidden');
        infoContainer.classList.add('hidden');
        textContainer.classList.add('hidden');
        streamLogs.innerHTML = '<div class="log-entry" style="color:#aaa;">> Initializing planner workflow...</div>';

        if (eventSource) {
            eventSource.close();
        }

        // SSE connection
        eventSource = new EventSource(`/api/specialist/stream?url=${encodeURIComponent(url)}&goal=${encodeURIComponent(goal)}`);

        eventSource.onmessage = (e) => {
            const data = JSON.parse(e.data);

            if (data.text) {
                const log = document.createElement('div');
                log.className = 'log-entry';
                // Very basic markdown handling for terminal
                log.innerHTML = data.text.replace(/\n/g, '<br/>');
                streamLogs.appendChild(log);
                streamLogs.scrollTop = streamLogs.scrollHeight;
            }

            if (data.infographicBase64 && goal === 'infographic') {
                infoImg.src = `data:${data.infographicMimeType};base64,${data.infographicBase64}`;
                infoContainer.classList.remove('hidden');
            }

            if (data.finalTextResult && (goal === 'summary' || goal === 'yt_shorts_script')) {
                textContent.innerHTML = safeRender(data.finalTextResult);
                textContainer.classList.remove('hidden');
                
                // Show completion log
                const log = document.createElement('div');
                log.className = 'log-entry';
                log.style.color = '#50fa7b';
                log.innerHTML = `> ✅ Goal '${goal}' reached successfully!`;
                streamLogs.appendChild(log);
                streamLogs.scrollTop = streamLogs.scrollHeight;
            }

            if (data.artifacts && data.artifacts.length > 0) {
                const log = document.createElement('div');
                log.className = 'log-entry';
                log.style.color = '#ffbd2e';
                log.innerHTML = `> Artifact updated: ${data.artifacts.join(', ')}`;
                streamLogs.appendChild(log);
                streamLogs.scrollTop = streamLogs.scrollHeight;
            }
        };

        eventSource.onerror = (e) => {
            pulse.classList.add('hidden');
            const log = document.createElement('div');
            log.className = 'log-entry';
            log.style.color = '#ff5f56';
            log.innerHTML = '> Stream ended or connection lost.';
            streamLogs.appendChild(log);
            eventSource.close();
        };
    });

    // Lightbox Logic
    const lightboxModal = document.getElementById('lightbox-modal');
    const lightboxImg = document.getElementById('lightbox-img');
    const lightboxClose = document.querySelector('.lightbox-close');

    infoImg.addEventListener('click', (e) => {
        if (e.target.src) {
            lightboxImg.src = e.target.src;
            lightboxModal.classList.remove('hidden');
        }
    });

    function closeLightbox() {
        lightboxModal.classList.add('hidden');
    }

    lightboxClose.addEventListener('click', closeLightbox);
    lightboxModal.addEventListener('click', (e) => {
        if (e.target === lightboxModal) {
            closeLightbox();
        }
    });
    
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !lightboxModal.classList.contains('hidden')) {
            closeLightbox();
        }
    });
});
