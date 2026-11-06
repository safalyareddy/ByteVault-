import { AuthProvider, useAuth } from './auth';
import { Layout } from './components/Layout';
import { AdminPage } from './pages/AdminPage';
import { EventsPage } from './pages/EventsPage';
import { LoginPage } from './pages/LoginPage';
import { NodesPage } from './pages/NodesPage';
import { ObjectDetailPage } from './pages/ObjectDetailPage';
import { ObjectsPage } from './pages/ObjectsPage';
import { OverviewPage } from './pages/OverviewPage';
import { RepairsPage } from './pages/RepairsPage';
import { useRoute } from './router';

export function App() {
  return (
    <AuthProvider>
      <Console />
    </AuthProvider>
  );
}

function Console() {
  const { session, checking } = useAuth();
  const [section = '', id] = useRoute();
  if (checking) return <div className="note muted" role="status">Checking session…</div>;
  if (!session) return <LoginPage />;

  const admin = session.role === 'ADMIN';
  const current = admin ? section || 'overview' : 'objects';
  let page;
  switch (current) {
    case 'nodes': page = <NodesPage />; break;
    case 'objects': page = id ? <ObjectDetailPage id={id} /> : <ObjectsPage />; break;
    case 'repairs': page = <RepairsPage />; break;
    case 'events': page = <EventsPage />; break;
    case 'admin': page = <AdminPage />; break;
    case 'overview': page = <OverviewPage />; break;
    default: page = <div className="note">Unknown page. <a href="#/">Go to start</a></div>;
  }
  if (!admin && section && section !== 'objects') {
    page = <div className="note warn">This page needs the ADMIN role. <a href="#/objects">Go to your objects</a></div>;
  }
  return <Layout current={current}>{page}</Layout>;
}
