import { useEffect } from 'react';
import { NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './state/auth';
import { MapScreen } from './screens/MapScreen';
import { ActivityScreen } from './screens/ActivityScreen';
import { LoginScreen } from './screens/LoginScreen';
import { ReportScreen } from './screens/ReportScreen';
import { AdminScreen } from './screens/AdminScreen';
import { ReplayScreen } from './screens/ReplayScreen';

/** The report screen is one screen with nothing else on it, so it has no tabs. */
const BARE_ROUTES = ['/report', '/login'];

export function App() {
  const { session, isReporter, isAdmin, expired, clearExpired } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  // A 401 mid-session drops the token. Send him to login without losing whatever
  // is sitting in the offline queue.
  useEffect(() => {
    if (expired && !BARE_ROUTES.includes(location.pathname)) {
      navigate('/login', { replace: true, state: { from: location.pathname } });
    }
  }, [expired, location.pathname, navigate]);

  useEffect(() => {
    if (expired && location.pathname === '/login') {
      const timer = window.setTimeout(clearExpired, 12000);
      return () => window.clearTimeout(timer);
    }
    return undefined;
  }, [expired, location.pathname, clearExpired]);

  const bare = BARE_ROUTES.includes(location.pathname);

  return (
    <div className="app">
      <div className="app__body">
        <Routes>
          <Route path="/" element={<MapScreen />} />
          <Route path="/activity" element={<ActivityScreen />} />
          <Route path="/login" element={<LoginScreen />} />
          <Route
            path="/report"
            element={isReporter ? <ReportScreen /> : <Navigate to="/login" replace />}
          />
          <Route path="/admin" element={isAdmin ? <AdminScreen /> : <Navigate to="/login" replace />} />
          <Route path="/admin/replay" element={isAdmin ? <ReplayScreen /> : <Navigate to="/login" replace />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>

      {!bare && (
        <nav className="tabs" aria-label="Main">
          <NavLink className="tabs__item" to="/" end>
            Map
          </NavLink>
          <NavLink className="tabs__item" to="/activity">
            Activity
          </NavLink>
          {isReporter && (
            <NavLink className="tabs__item" to="/report">
              Report
            </NavLink>
          )}
          {isAdmin && (
            <NavLink className="tabs__item" to="/admin">
              Admin
            </NavLink>
          )}
          {!session && (
            <NavLink className="tabs__item" to="/login">
              Sign in
            </NavLink>
          )}
        </nav>
      )}
    </div>
  );
}
