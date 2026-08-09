(function () {
  'use strict';

  var STRATEGY_LABELS = {
    PERSONAL_TAG: 'Personal E-ZPass',
    COMPANY_PER_CROSSING: 'Pay per crossing',
    COMPANY_UNLIMITED: 'Unlimited toll plan',
    NO_ARRANGEMENT: 'No toll arrangement needed'
  };

  var COMPANY_CODES = ['AVIS', 'BUDGET', 'DOLLAR', 'ENTERPRISE', 'HERTZ', 'THRIFTY'];

  var WEEKDAY_NAMES = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  var MONTH_NAMES = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  var MPG_BY_CLASS = {
    ECONOMY: 36, COMPACT: 33, MIDSIZE: 30.5, STANDARD: 28.5,
    FULLSIZE: 27, SUV: 24, MINIVAN: 22, LUXURY: 25
  };

  var lastTripDates = null;
  var lastTripPlaces = null;
  var lastDistanceMiles = null;

  function formatUSD(cents) {
    if (typeof cents !== 'number' || isNaN(cents)) return 'n/a';
    return (cents / 100).toLocaleString('en-US', { style: 'currency', currency: 'USD' });
  }

  function pad2(n) {
    return n < 10 ? '0' + n : '' + n;
  }

  function toLocalInputValue(date) {
    return date.getFullYear() + '-' + pad2(date.getMonth() + 1) + '-' + pad2(date.getDate()) +
      'T' + pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  }

  function upcomingFridayAt(hour, minute) {
    var now = new Date();
    var day = now.getDay();
    var diff = (5 - day + 7) % 7;
    var candidate = new Date(now.getFullYear(), now.getMonth(), now.getDate() + diff, hour, minute, 0, 0);
    if (candidate.getTime() <= now.getTime()) {
      candidate = new Date(candidate.getFullYear(), candidate.getMonth(), candidate.getDate() + 7, hour, minute, 0, 0);
    }
    return candidate;
  }

  function prefillDates() {
    var departure = upcomingFridayAt(18, 0);
    var ret = new Date(departure.getFullYear(), departure.getMonth(), departure.getDate() + 2, 14, 0, 0, 0);
    document.getElementById('departureAt').value = toLocalInputValue(departure);
    document.getElementById('returnAt').value = toLocalInputValue(ret);
  }

  function setupModeToggle() {
    var autoRadio = document.getElementById('mode-auto');
    var manualRadio = document.getElementById('mode-manual');
    var manualRates = document.getElementById('manual-rates');
    var carClassSelect = document.getElementById('carClass');
    var allClassesOption = document.getElementById('carClass-all');

    function applyMode() {
      var manual = manualRadio.checked;
      manualRates.hidden = !manual;
      allClassesOption.disabled = manual;
      if (manual && carClassSelect.value === '') {
        carClassSelect.value = 'MIDSIZE';
      }
    }

    autoRadio.addEventListener('change', applyMode);
    manualRadio.addEventListener('change', applyMode);
    applyMode();
  }

  function collectManualOverrides() {
    var overrides = [];
    COMPANY_CODES.forEach(function (code) {
      var row = document.querySelector('.manual-rate-row[data-company-code="' + code + '"]');
      var input = document.getElementById('rate-' + code);
      var raw = input.value.trim();
      if (raw === '') return;
      var dollars = parseFloat(raw);
      if (!isFinite(dollars) || dollars <= 0) return;
      overrides.push({
        companyCode: code,
        companyName: row.getAttribute('data-company-name'),
        totalCents: Math.round(dollars * 100)
      });
    });
    return overrides;
  }

  function showError(message) {
    var banner = document.getElementById('error-banner');
    var text = document.getElementById('error-banner-text');
    text.textContent = message;
    banner.hidden = false;
  }

  function hideError() {
    document.getElementById('error-banner').hidden = true;
  }

  function setLoading(isLoading) {
    var btn = document.getElementById('submit-btn');
    var indicator = document.getElementById('loading-indicator');
    btn.disabled = isLoading;
    if (isLoading) {
      btn.classList.add('is-loading');
    } else {
      btn.classList.remove('is-loading');
    }
    indicator.hidden = !isLoading;
  }

  function badge(kind, text) {
    var span = document.createElement('span');
    span.className = 'badge badge-' + kind;
    span.textContent = text;
    return span;
  }

  function renderFreshnessNote(freshness) {
    var container = document.getElementById('freshness-note');
    container.innerHTML = '';
    if (!freshness) {
      container.hidden = true;
      return;
    }
    var labels = { route: 'Route', tolls: 'Tolls', hotel: 'Hotel' };
    var any = false;
    Object.keys(labels).forEach(function (key) {
      var value = freshness[key];
      if (value && value !== 'FRESH') {
        any = true;
        var kind = value === 'UNAVAILABLE' ? 'unavailable' : 'stale';
        container.appendChild(badge(kind, labels[key] + ': ' + value.toLowerCase()));
      }
    });
    container.hidden = !any;
  }

  function titleCase(code) {
    if (!code) return '';
    return code.charAt(0).toUpperCase() + code.slice(1).toLowerCase();
  }

  function renderRecommendation(recommendation, winner) {
    var ticket = document.getElementById('recommendation');
    if (!recommendation) {
      ticket.hidden = true;
      return;
    }
    ticket.hidden = false;

    var name = winner
      ? (winner.companyName || winner.companyCode) + ' · ' + titleCase(winner.carClassCode)
      : titleCase(recommendation.companyCode);
    document.getElementById('recommendation-name').textContent = name;

    var noteEl = document.getElementById('recommendation-note');
    if (winner && winner.strategy) {
      noteEl.textContent = 'Wins with ' + (STRATEGY_LABELS[winner.strategy.kind] || winner.strategy.kind).toLowerCase();
      noteEl.hidden = false;
    } else {
      noteEl.hidden = true;
    }

    document.getElementById('recommendation-total').textContent = formatUSD(recommendation.trueTotalCents);
    var savingsEl = document.getElementById('recommendation-savings');
    if (typeof recommendation.savingsOverNextCents === 'number' && recommendation.savingsOverNextCents > 0) {
      savingsEl.textContent = 'Saves ' + formatUSD(recommendation.savingsOverNextCents) + ' vs next';
      savingsEl.hidden = false;
    } else {
      savingsEl.textContent = '';
      savingsEl.hidden = true;
    }
  }

  function breakdownRow(label, cents, className) {
    var tr = document.createElement('tr');
    if (className) {
      tr.className = className;
    }
    var labelTd = document.createElement('td');
    labelTd.textContent = label;
    var valueTd = document.createElement('td');
    valueTd.textContent = formatUSD(cents);
    tr.appendChild(labelTd);
    tr.appendChild(valueTd);
    return tr;
  }

  function groupHeadRow(text) {
    var tr = document.createElement('tr');
    tr.className = 'breakdown-group';
    var td = document.createElement('td');
    td.colSpan = 2;
    td.textContent = text;
    tr.appendChild(td);
    return tr;
  }

  function fuelLabel(option) {
    var mpg = MPG_BY_CLASS[option.carClassCode];
    if (!mpg || !lastDistanceMiles) {
      return 'Fuel';
    }
    return 'Fuel · ' + (lastDistanceMiles / mpg).toFixed(1) + ' gal at ' + mpg + ' mpg';
  }

  function tollSavings(strategy) {
    if (!strategy || !Array.isArray(strategy.candidates) || strategy.candidates.length < 2) {
      return null;
    }
    var sorted = strategy.candidates.slice().sort(function (a, b) { return a.totalCents - b.totalCents; });
    return {
      cents: sorted[1].totalCents - sorted[0].totalCents,
      vsMethod: STRATEGY_LABELS[sorted[1].strategy] || sorted[1].strategy
    };
  }

  function breakdownRowNoted(label, cents, note, className) {
    var tr = document.createElement('tr');
    if (className) {
      tr.className = className;
    }
    var labelTd = document.createElement('td');
    var main = document.createElement('span');
    main.textContent = label;
    labelTd.appendChild(main);
    if (note) {
      var sub = document.createElement('span');
      sub.className = 'breakdown-subnote';
      sub.textContent = note;
      labelTd.appendChild(sub);
    }
    var valueTd = document.createElement('td');
    valueTd.textContent = formatUSD(cents);
    tr.appendChild(labelTd);
    tr.appendChild(valueTd);
    return tr;
  }

  function tollFeeNote(option) {
    var s = option.strategy;
    if (!s) {
      return '';
    }
    var fee = option.tollProgramFeeCents;
    var toll = option.tollTotalCents;
    var winner = (s.candidates || []).find(function (c) {
      return c.strategy === s.kind && c.programName === s.programName;
    });
    var days = winner ? winner.feeDays : 0;
    var perDay = days > 0 ? formatUSD(Math.round(fee / days)) : null;

    if (s.kind === 'COMPANY_UNLIMITED') {
      var daysLabel = days === 1 ? '1 day' : days + ' days';
      return perDay
        ? perDay + '/day for ' + daysLabel + ', every toll included'
        : 'Flat plan fee of ' + formatUSD(fee) + ', every toll included';
    }
    if (s.kind === 'PERSONAL_TAG') {
      return 'Tolls at your own E-ZPass rate, no company fee';
    }
    if (s.kind === 'NO_ARRANGEMENT') {
      return 'No tolled crossings on this route';
    }
    if (s.kind === 'COMPANY_PER_CROSSING') {
      var tollDays = days === 1 ? '1 toll day' : days + ' toll days';
      return (perDay ? perDay + '/day for ' + tollDays + ' = ' + formatUSD(fee) + ' admin' : formatUSD(fee) + ' admin')
        + ', plus ' + formatUSD(toll) + ' in tolls at the program rate';
    }
    return '';
  }

  var COST_CATEGORIES = [
    { label: 'Rental', varName: '--cost-rental' },
    { label: 'Tolls', varName: '--cost-tolls' },
    { label: 'Congestion', varName: '--cost-congestion' },
    { label: 'Fuel', varName: '--cost-fuel' },
    { label: 'Hotel', varName: '--cost-hotel' }
  ];

  function costComponents(option) {
    return [
      option.rentalCents,
      option.tollProgramFeeCents + option.tollTotalCents,
      option.congestionCents,
      option.fuelCents,
      option.hotelCents
    ];
  }

  /**
   * A stacked proportion bar of where the true total goes, so the audience sees the composition at
   * a glance. Colors match the shared legend, the dataviz skill's validated categorical set.
   */
  function renderCostBar(option) {
    var total = option.trueTotalCents;
    if (!total || total <= 0) {
      return null;
    }
    var cents = costComponents(option);
    var bar = document.createElement('div');
    bar.className = 'cost-bar';
    bar.setAttribute('role', 'img');
    var parts = [];
    COST_CATEGORIES.forEach(function (cat, i) {
      if (cents[i] <= 0) {
        return;
      }
      var pct = cents[i] / total * 100;
      var seg = document.createElement('span');
      seg.className = 'cost-seg';
      seg.style.flexGrow = String(cents[i]);
      seg.style.background = 'var(' + cat.varName + ')';
      seg.title = cat.label + ' ' + formatUSD(cents[i]) + ' (' + Math.round(pct) + '%)';
      bar.appendChild(seg);
      parts.push(cat.label + ' ' + Math.round(pct) + ' percent');
    });
    bar.setAttribute('aria-label', 'Cost composition, ' + parts.join(', '));
    return bar;
  }

  function renderResultsLegend() {
    var wrap = document.getElementById('results-legend');
    wrap.innerHTML = '';
    var intro = document.createElement('p');
    intro.className = 'results-legend-intro';
    intro.textContent = 'Real all-in cost per company. The bar shows where the money goes.';
    wrap.appendChild(intro);
    var legend = document.createElement('div');
    legend.className = 'cost-legend';
    COST_CATEGORIES.forEach(function (cat) {
      var item = document.createElement('span');
      item.className = 'cost-legend-item';
      var sw = document.createElement('span');
      sw.className = 'cost-swatch';
      sw.style.background = 'var(' + cat.varName + ')';
      item.appendChild(sw);
      item.appendChild(document.createTextNode(cat.label));
      legend.appendChild(item);
    });
    wrap.appendChild(legend);
    wrap.hidden = false;
  }

  function renderTollHero(option) {
    var hero = document.createElement('div');
    hero.className = 'toll-hero';

    var left = document.createElement('div');
    var eyebrow = document.createElement('span');
    eyebrow.className = 'toll-hero-eyebrow';
    eyebrow.textContent = 'Cheapest toll strategy';
    var method = document.createElement('span');
    method.className = 'toll-hero-method';
    method.textContent = STRATEGY_LABELS[option.strategy.kind] || option.strategy.kind;
    left.appendChild(eyebrow);
    left.appendChild(method);

    hero.appendChild(left);

    var savings = tollSavings(option.strategy);
    if (savings && savings.cents > 0) {
      var pill = document.createElement('span');
      pill.className = 'toll-hero-savings';
      pill.textContent = 'Saves ' + formatUSD(savings.cents) + ' vs ' + savings.vsMethod.toLowerCase();
      hero.appendChild(pill);
    }
    return hero;
  }

  function formatDateLabel(date) {
    return WEEKDAY_NAMES[date.getDay()] + ', ' + MONTH_NAMES[date.getMonth()] + ' ' + date.getDate();
  }

  function shortPlace(value) {
    return (value || '').split(',')[0].trim();
  }

  function renderTripHeader(context) {
    var el = document.getElementById('trip-header');
    el.innerHTML = '';
    if (!context || !lastTripDates) {
      el.hidden = true;
      return;
    }

    var route = document.createElement('div');
    route.className = 'trip-route';
    var from = document.createElement('span');
    from.textContent = lastTripPlaces ? shortPlace(lastTripPlaces.from) : 'Pickup';
    var arrow = document.createElement('span');
    arrow.className = 'trip-route-arrow';
    arrow.textContent = '→';
    var to = document.createElement('span');
    to.textContent = lastTripPlaces ? shortPlace(lastTripPlaces.to) : 'Destination';
    route.appendChild(from);
    route.appendChild(arrow);
    route.appendChild(to);

    var meta = [];
    meta.push(formatDateLabel(lastTripDates.departure) + ' to ' + formatDateLabel(lastTripDates.ret));
    if (typeof context.rentalDays === 'number') {
      meta.push(context.rentalDays + (context.rentalDays === 1 ? ' day' : ' days'));
    }
    if (typeof context.totalDistanceMiles === 'number' && context.totalDistanceMiles > 0) {
      meta.push(Math.round(context.totalDistanceMiles) + ' mi round trip');
    }
    var metaEl = document.createElement('div');
    metaEl.className = 'trip-meta';
    metaEl.textContent = meta.join('   ·   ');

    el.appendChild(route);
    el.appendChild(metaEl);
    el.hidden = false;
  }

  /**
   * The strategy engine already evaluated and retained every candidate, personal tag, per
   * crossing, unlimited plan, no arrangement, not just the winner. Surfacing all of them is what
   * makes the toll strategy decision, the thesis's core engineering claim, inspectable per option
   * rather than asserted.
   */
  function renderStrategyCompare(option) {
    var strategy = option.strategy;
    if (!strategy || !Array.isArray(strategy.candidates) || strategy.candidates.length < 2) {
      return null;
    }

    var wrapper = document.createElement('div');
    wrapper.className = 'strategy-compare';

    var detailId = 'strategy-compare-' + option.companyCode + '-' + option.carClassCode;
    var candidateCount = strategy.candidates.length;

    var toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'strategy-compare-toggle';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.setAttribute('aria-controls', detailId);

    var chevron = document.createElement('span');
    chevron.className = 'strategy-compare-chevron';
    chevron.setAttribute('aria-hidden', 'true');
    chevron.textContent = '▾';
    var toggleLabel = document.createElement('span');
    toggleLabel.textContent = 'Compare all ' + candidateCount + ' toll strategies';
    toggle.appendChild(chevron);
    toggle.appendChild(toggleLabel);

    var detail = document.createElement('div');
    detail.id = detailId;
    detail.className = 'strategy-compare-detail';
    detail.hidden = true;

    var table = document.createElement('table');
    table.className = 'strategy-compare-table';
    var thead = document.createElement('thead');
    var headRow = document.createElement('tr');
    ['Strategy', 'Fee', 'Tolls', 'Congestion', 'Total'].forEach(function (label) {
      var th = document.createElement('th');
      th.textContent = label;
      headRow.appendChild(th);
    });
    thead.appendChild(headRow);

    var tbody = document.createElement('tbody');
    strategy.candidates.forEach(function (candidate) {
      var isWinner = candidate.strategy === strategy.kind &&
        candidate.programName === strategy.programName &&
        candidate.totalCents === strategy.totalCents;
      var tr = document.createElement('tr');
      if (isWinner) {
        tr.className = 'strategy-compare-row-winner';
      }
      var nameTd = document.createElement('td');
      nameTd.textContent = STRATEGY_LABELS[candidate.strategy] || candidate.strategy;
      if (isWinner) {
        var stamp = document.createElement('span');
        stamp.className = 'strategy-compare-winner-stamp';
        stamp.textContent = 'winner';
        nameTd.appendChild(stamp);
      }
      tr.appendChild(nameTd);
      [candidate.feeCents, candidate.tollCents, candidate.congestionCents, candidate.totalCents].forEach(function (cents) {
        var td = document.createElement('td');
        td.textContent = formatUSD(cents);
        tr.appendChild(td);
      });
      tbody.appendChild(tr);
    });

    table.appendChild(thead);
    table.appendChild(tbody);
    detail.appendChild(table);

    var legend = document.createElement('p');
    legend.className = 'strategy-compare-legend';
    legend.textContent = 'Fee is the admin or plan charge. Tolls are the crossings at the program rate.';
    detail.appendChild(legend);

    toggle.addEventListener('click', function () {
      var expanded = toggle.getAttribute('aria-expanded') === 'true';
      toggle.setAttribute('aria-expanded', String(!expanded));
      detail.hidden = expanded;
      toggleLabel.textContent = expanded ? ('Compare all ' + candidateCount + ' toll strategies') : 'Hide comparison';
    });

    wrapper.appendChild(toggle);
    wrapper.appendChild(detail);
    return wrapper;
  }

  function renderOptionCard(option, isWinner) {
    var card = document.createElement('article');
    card.className = 'option-card' + (isWinner ? ' is-winner' : '');

    if (isWinner) {
      var ribbon = document.createElement('span');
      ribbon.className = 'option-ribbon';
      ribbon.textContent = 'Best value';
      card.appendChild(ribbon);
    }

    var header = document.createElement('div');
    header.className = 'option-header';

    var headerLeft = document.createElement('div');
    headerLeft.className = 'option-header-left';
    var company = document.createElement('span');
    company.className = 'option-company';
    company.textContent = option.companyName || option.companyCode || 'Unknown company';
    var carClass = document.createElement('span');
    carClass.className = 'option-class';
    carClass.textContent = option.carClassCode || '';
    headerLeft.appendChild(company);
    headerLeft.appendChild(carClass);

    var badges = document.createElement('div');
    badges.className = 'option-badges';
    if (option.provenance && option.provenance.rentalSource) {
      badges.appendChild(badge('provenance', option.provenance.rentalSource));
    }
    if (option.provenance && option.provenance.fuelPriceIsFallback) {
      badges.appendChild(badge('stale', 'Fuel estimate'));
    }
    if (option.partial) {
      badges.appendChild(badge('partial', 'Partial'));
    }

    header.appendChild(headerLeft);
    header.appendChild(badges);

    var body = document.createElement('div');
    body.className = 'option-body';

    if (option.strategy) {
      body.appendChild(renderTollHero(option));
    }

    var costBar = renderCostBar(option);
    if (costBar) {
      body.appendChild(costBar);
    }

    var table = document.createElement('table');
    table.className = 'breakdown';
    var tbody = document.createElement('tbody');

    tbody.appendChild(groupHeadRow('This rental'));
    tbody.appendChild(breakdownRow('Base rental', option.rentalCents));
    var tollPayCents = option.tollProgramFeeCents + option.tollTotalCents;
    var tollLabel = option.strategy
      ? 'Tolls · ' + (STRATEGY_LABELS[option.strategy.kind] || option.strategy.kind).toLowerCase()
      : 'Tolls';
    tbody.appendChild(breakdownRowNoted(tollLabel, tollPayCents, tollFeeNote(option), 'breakdown-decision'));

    tbody.appendChild(groupHeadRow('Same for every option'));
    var congestionNote = option.congestionCents > 0 ? 'NYC Congestion Relief Zone, once per day' : null;
    tbody.appendChild(breakdownRowNoted('Congestion pricing', option.congestionCents, congestionNote));
    tbody.appendChild(breakdownRow(fuelLabel(option), option.fuelCents));
    tbody.appendChild(breakdownRow('Hotel', option.hotelCents));

    tbody.appendChild(breakdownRow('True total', option.trueTotalCents, 'total-row'));
    table.appendChild(tbody);
    body.appendChild(table);

    if (option.strategy) {
      var strategyPanel = document.createElement('div');
      strategyPanel.className = 'strategy-panel';
      var explanation = document.createElement('p');
      explanation.className = 'strategy-explanation';
      explanation.textContent = option.strategy.explanation || '';
      strategyPanel.appendChild(explanation);
      var compare = renderStrategyCompare(option);
      if (compare) {
        strategyPanel.appendChild(compare);
      }
      body.appendChild(strategyPanel);
    }

    card.appendChild(header);
    card.appendChild(body);
    return card;
  }

  function renderResults(data) {
    document.getElementById('empty-state').hidden = true;
    var results = document.getElementById('results');
    results.hidden = false;

    lastDistanceMiles = (data.context && typeof data.context.totalDistanceMiles === 'number')
      ? data.context.totalDistanceMiles : null;
    renderTripHeader(data.context);
    renderFreshnessNote(data.freshness);
    renderRecommendation(data.recommendation, (data.options || [])[0]);
    renderResultsLegend();

    var list = document.getElementById('options-list');
    list.innerHTML = '';
    var options = data.options || [];
    if (options.length === 0) {
      var empty = document.createElement('p');
      empty.textContent = 'No rental options were returned for this trip.';
      list.appendChild(empty);
    } else {
      options.forEach(function (option, index) {
        list.appendChild(renderOptionCard(option, index === 0));
      });
    }

    document.getElementById('jump-to-results').hidden = false;
  }

  function fail(message, fieldId) {
    var error = new Error(message);
    error.fieldId = fieldId;
    throw error;
  }

  function buildRequestBody() {
    var mode = document.querySelector('input[name="mode"]:checked').value;
    var departureInput = document.getElementById('departureAt');
    var returnInput = document.getElementById('returnAt');

    if (!departureInput.value) {
      fail('Pickup date and time are required.', 'departureAt');
    }
    if (!returnInput.value) {
      fail('Return date and time are required.', 'returnAt');
    }

    var originLat = parseFloat(document.getElementById('originLat').value);
    var originLng = parseFloat(document.getElementById('originLng').value);
    var destLat = parseFloat(document.getElementById('destLat').value);
    var destLng = parseFloat(document.getElementById('destLng').value);
    var pickupLocationCode = document.getElementById('pickupLocationCode').value.trim();
    var destinationCode = document.getElementById('destinationCode').value.trim();

    if (isNaN(originLat) || isNaN(originLng)) {
      fail('Origin latitude and longitude must be valid numbers.', 'originLat');
    }
    if (isNaN(destLat) || isNaN(destLng)) {
      fail('Destination latitude and longitude must be valid numbers.', 'destLat');
    }
    if (!pickupLocationCode) {
      fail('Pickup location code is required.', 'pickupLocationCode');
    }
    if (!destinationCode) {
      fail('Destination location code is required.', 'destinationCode');
    }

    var body = {
      originLat: originLat,
      originLng: originLng,
      pickupLocationCode: pickupLocationCode,
      destLat: destLat,
      destLng: destLng,
      destinationCode: destinationCode,
      departureAt: new Date(departureInput.value).toISOString(),
      returnAt: new Date(returnInput.value).toISOString(),
      hasPersonalEzpass: document.getElementById('hasPersonalEzpass').checked,
      carClass: document.getElementById('carClass').value,
      rentalRateOverrides: null
    };

    if (mode === 'manual') {
      var overrides = collectManualOverrides();
      if (overrides.length === 0) {
        fail('Manual mode is selected. Enter at least one company rate, or switch to Auto.', 'rate-AVIS');
      }
      if (!body.carClass) {
        fail('A specific car class is required when using manual rates.', 'carClass');
      }
      body.rentalRateOverrides = overrides;
    }

    return body;
  }

  function focusField(fieldId) {
    if (!fieldId) return;
    var field = document.getElementById(fieldId);
    if (!field || typeof field.focus !== 'function') return;
    if (typeof field.scrollIntoView === 'function') {
      field.scrollIntoView({ block: 'center' });
    }
    field.focus();
  }

  function submitTrip(event) {
    event.preventDefault();
    hideError();

    var requestBody;
    try {
      requestBody = buildRequestBody();
    } catch (validationError) {
      showError(validationError.message);
      focusField(validationError.fieldId);
      return;
    }

    lastTripDates = {
      departure: new Date(requestBody.departureAt),
      ret: new Date(requestBody.returnAt)
    };
    lastTripPlaces = {
      from: document.getElementById('pickup-search').value,
      to: document.getElementById('dest-search').value
    };

    setLoading(true);

    fetch('/api/v1/trips/plan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(requestBody)
    })
      .then(function (response) {
        return response.json().catch(function () {
          return null;
        }).then(function (data) {
          if (!response.ok) {
            var message = (data && data.error) ? data.error : ('Request failed with status ' + response.status);
            throw new Error(message);
          }
          return data;
        });
      })
      .then(function (data) {
        renderResults(data);
      })
      .catch(function (err) {
        showError(err.message || 'Something went wrong while planning this trip.');
      })
      .finally(function () {
        setLoading(false);
        refreshMetrics();
      });
  }

  function metricCount(path) {
    return fetch(path)
      .then(function (response) {
        if (!response.ok) return null;
        return response.json();
      })
      .then(function (data) {
        if (!data || !Array.isArray(data.measurements)) return null;
        var m = data.measurements.find(function (x) { return x.statistic === 'COUNT'; });
        return m ? m.value : null;
      })
      .catch(function () {
        return null;
      });
  }

  function metricHttpLatency() {
    return fetch('/actuator/metrics/http.server.requests')
      .then(function (response) {
        if (!response.ok) return null;
        return response.json();
      })
      .then(function (data) {
        if (!data || !Array.isArray(data.measurements)) return null;
        var count = data.measurements.find(function (x) { return x.statistic === 'COUNT'; });
        var total = data.measurements.find(function (x) { return x.statistic === 'TOTAL_TIME'; });
        if (!count || !count.value) return null;
        return (total.value / count.value) * 1000;
      })
      .catch(function () {
        return null;
      });
  }

  function setStrategyBar(kind, count, maxCount) {
    var row = document.querySelector('.strategy-bar-row[data-kind="' + kind + '"]');
    if (!row) return;
    var fill = row.querySelector('.strategy-bar-fill');
    var countEl = row.querySelector('.strategy-bar-count');
    if (count === null) {
      fill.style.width = '0%';
      countEl.textContent = 'n/a';
      return;
    }
    var pct = maxCount > 0 ? (count / maxCount) * 100 : 0;
    fill.style.width = pct + '%';
    countEl.textContent = String(count);
  }

  function refreshMetrics() {
    try {
      Promise.all([
        metricCount('/actuator/metrics/truecost.cache.request?tag=outcome:l1_hit'),
        metricCount('/actuator/metrics/truecost.cache.request?tag=outcome:l2_hit'),
        metricCount('/actuator/metrics/truecost.cache.request?tag=outcome:stale'),
        metricCount('/actuator/metrics/truecost.cache.request?tag=outcome:miss')
      ]).then(function (results) {
        var l1 = results[0] || 0, l2 = results[1] || 0, stale = results[2] || 0, miss = results[3] || 0;
        var hitRateEl = document.getElementById('metric-hit-rate');
        var countsEl = document.getElementById('metric-hit-rate-counts');
        var hits = l1 + l2 + stale;
        var totalRequests = hits + miss;
        if (totalRequests === 0) {
          hitRateEl.textContent = 'n/a';
          countsEl.textContent = 'no data yet';
        } else {
          var rate = (hits / totalRequests) * 100;
          hitRateEl.textContent = rate.toFixed(1) + '%';
          countsEl.textContent = 'l1 ' + l1 + ' · l2 ' + l2 + ' · stale ' + stale + ' · miss ' + miss;
        }
      }).catch(function () {
        document.getElementById('metric-hit-rate').textContent = 'n/a';
        document.getElementById('metric-hit-rate-counts').textContent = 'no data yet';
      });

      metricCount('/actuator/metrics/truecost.cache.singleflight.coalesced').then(function (value) {
        document.getElementById('metric-coalesced').textContent = value === null ? 'n/a' : String(value);
      }).catch(function () {
        document.getElementById('metric-coalesced').textContent = 'n/a';
      });

      metricHttpLatency().then(function (ms) {
        document.getElementById('metric-latency').textContent = ms === null ? 'n/a' : (Math.round(ms) + ' ms');
      }).catch(function () {
        document.getElementById('metric-latency').textContent = 'n/a';
      });

      Promise.all([
        metricCount('/actuator/metrics/truecost.strategy.chosen?tag=kind:PERSONAL_TAG'),
        metricCount('/actuator/metrics/truecost.strategy.chosen?tag=kind:COMPANY_PER_CROSSING'),
        metricCount('/actuator/metrics/truecost.strategy.chosen?tag=kind:COMPANY_UNLIMITED'),
        metricCount('/actuator/metrics/truecost.strategy.chosen?tag=kind:NO_ARRANGEMENT')
      ]).then(function (results) {
        var kinds = ['PERSONAL_TAG', 'COMPANY_PER_CROSSING', 'COMPANY_UNLIMITED', 'NO_ARRANGEMENT'];
        var maxCount = Math.max.apply(null, results.map(function (v) { return v || 0; }));
        kinds.forEach(function (kind, i) {
          setStrategyBar(kind, results[i], maxCount);
        });
      }).catch(function () {
        ['PERSONAL_TAG', 'COMPANY_PER_CROSSING', 'COMPANY_UNLIMITED', 'NO_ARRANGEMENT'].forEach(function (kind) {
          setStrategyBar(kind, null, 0);
        });
      });
    } catch (e) {
      // Metrics panel must never break the trip-planning flow.
    }
  }

  function debounce(fn, ms) {
    var timer;
    return function () {
      var args = arguments;
      clearTimeout(timer);
      timer = setTimeout(function () { fn.apply(null, args); }, ms);
    };
  }

  function placeLabel(props) {
    var parts = [];
    if (props.name) parts.push(props.name);
    if (props.city && props.city !== props.name) parts.push(props.city);
    else if (props.county && props.county !== props.name && !props.city) parts.push(props.county);
    if (props.state) parts.push(props.state);
    if (props.country && props.country !== 'United States') parts.push(props.country);
    return parts.join(', ');
  }

  function placeCode(props) {
    var base = props.name || props.city || props.state || 'loc';
    var code = base.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
    return code || 'LOC';
  }

  /**
   * Free-text place search via Photon, the OpenStreetMap geocoder, so the tedious latitude and
   * longitude fields become a searchable dropdown. The selection writes into hidden lat, lng, and
   * code fields the request builder already reads, so nothing downstream changes.
   */
  function setupPlaceSearch(inputId, resultsId, latId, lngId, codeId) {
    var input = document.getElementById(inputId);
    var results = document.getElementById(resultsId);
    var latEl = document.getElementById(latId);
    var lngEl = document.getElementById(lngId);
    var codeEl = document.getElementById(codeId);
    var features = [];
    var activeIndex = -1;

    function close() {
      results.hidden = true;
      results.innerHTML = '';
      input.setAttribute('aria-expanded', 'false');
      features = [];
      activeIndex = -1;
    }

    function choose(feature) {
      var coords = feature.geometry.coordinates;
      lngEl.value = coords[0];
      latEl.value = coords[1];
      codeEl.value = placeCode(feature.properties);
      input.value = placeLabel(feature.properties);
      close();
    }

    function message(text) {
      results.innerHTML = '';
      var li = document.createElement('li');
      li.className = 'place-result place-result-empty';
      li.textContent = text;
      results.appendChild(li);
      results.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    }

    function render() {
      if (features.length === 0) {
        message('No matches');
        return;
      }
      results.innerHTML = '';
      features.forEach(function (f, i) {
        var li = document.createElement('li');
        li.className = 'place-result' + (i === activeIndex ? ' is-active' : '');
        li.setAttribute('role', 'option');
        li.textContent = placeLabel(f.properties);
        li.addEventListener('mousedown', function (e) {
          e.preventDefault();
          choose(f);
        });
        results.appendChild(li);
      });
      results.hidden = false;
      input.setAttribute('aria-expanded', 'true');
    }

    var runSearch = debounce(function () {
      var q = input.value.trim();
      if (q.length < 3) {
        close();
        return;
      }
      fetch('https://photon.komoot.io/api/?limit=6&lang=en&q=' + encodeURIComponent(q))
        .then(function (r) { return r.ok ? r.json() : null; })
        .then(function (data) {
          features = (data && Array.isArray(data.features)) ? data.features : [];
          activeIndex = -1;
          render();
        })
        .catch(function () {
          features = [];
          message('Search unavailable, type coordinates are still set from your last choice');
        });
    }, 280);

    input.addEventListener('input', runSearch);
    input.addEventListener('keydown', function (e) {
      if (results.hidden) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        activeIndex = Math.min(activeIndex + 1, features.length - 1);
        render();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        activeIndex = Math.max(activeIndex - 1, 0);
        render();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (features.length > 0) {
          choose(features[activeIndex >= 0 ? activeIndex : 0]);
        }
      } else if (e.key === 'Escape') {
        close();
      }
    });
    input.addEventListener('blur', function () {
      setTimeout(close, 120);
    });
  }

  var PRESETS = {
    philly: {
      pickup: 'New York, NY', originLat: 40.7505, originLng: -73.9934, pickupCode: 'EWR',
      dest: 'Philadelphia, PA', destLat: 39.95, destLng: -75.16, destCode: 'PHL',
      ezpass: false, carClass: 'MIDSIZE'
    },
    phillyEz: {
      pickup: 'New York, NY', originLat: 40.7505, originLng: -73.9934, pickupCode: 'EWR',
      dest: 'Philadelphia, PA', destLat: 39.95, destLng: -75.16, destCode: 'PHL',
      ezpass: true, carClass: 'MIDSIZE'
    },
    montauk: {
      pickup: 'Brooklyn, NY', originLat: 40.6782, originLng: -73.9442, pickupCode: 'BKN',
      dest: 'Montauk, NY', destLat: 41.0362, destLng: -71.9509, destCode: 'MTK',
      ezpass: false, carClass: 'MIDSIZE'
    }
  };

  /**
   * One click loads a known-good trip and plans it, so the demo can show the winning toll strategy
   * shift across scenarios, a company plan, a personal tag, and a toll free route, without typing.
   */
  function applyPreset(name) {
    var p = PRESETS[name];
    if (!p) return;
    hideError();
    document.getElementById('pickup-search').value = p.pickup;
    document.getElementById('originLat').value = p.originLat;
    document.getElementById('originLng').value = p.originLng;
    document.getElementById('pickupLocationCode').value = p.pickupCode;
    document.getElementById('dest-search').value = p.dest;
    document.getElementById('destLat').value = p.destLat;
    document.getElementById('destLng').value = p.destLng;
    document.getElementById('destinationCode').value = p.destCode;
    document.getElementById('hasPersonalEzpass').checked = p.ezpass;
    document.getElementById('carClass').value = p.carClass;

    var autoRadio = document.getElementById('mode-auto');
    autoRadio.checked = true;
    autoRadio.dispatchEvent(new Event('change'));

    document.getElementById('trip-form').requestSubmit();
  }

  document.addEventListener('DOMContentLoaded', function () {
    prefillDates();
    setupModeToggle();
    setupPlaceSearch('pickup-search', 'pickup-results', 'originLat', 'originLng', 'pickupLocationCode');
    setupPlaceSearch('dest-search', 'dest-results', 'destLat', 'destLng', 'destinationCode');
    Array.prototype.forEach.call(document.querySelectorAll('.scenario-btn'), function (btn) {
      btn.addEventListener('click', function () { applyPreset(btn.getAttribute('data-preset')); });
    });
    document.getElementById('trip-form').addEventListener('submit', submitTrip);
    document.getElementById('error-banner-dismiss').addEventListener('click', hideError);

    refreshMetrics();
    setInterval(refreshMetrics, 4000);
  });
})();
